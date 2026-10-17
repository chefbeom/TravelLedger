package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.ledger.adapter.out.persistence.LedgerAnalysisDatasetQueryAdapter;
import com.playdata.calen.ledger.application.port.in.PrepareLedgerAnalysisDatasetUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData.*;
import com.playdata.calen.ledger.domain.policy.LedgerAnalysisCalendarPolicy;
import com.playdata.calen.ledger.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "spring.datasource.url=jdbc:h2:mem:analysis-preparation;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LedgerAnalysisPreparationIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired LedgerEntryRepository entries;
    @Autowired CategoryGroupRepository groups;
    @Autowired PaymentMethodRepository payments;
    @Autowired PrepareLedgerAnalysisDatasetUseCase preparation;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    @MockitoSpyBean LedgerAnalysisDatasetQueryAdapter store;
    @MockitoBean LedgerSpendingAnalysisPort remote;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean TravelClusterRefreshCoordinator coordinator;
    LedgerAnalysisDatasetQueryAdapter target;
    final DateRange primary = new DateRange(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 11));
    @BeforeEach void verifyPrivateDatabaseAndResetOnlyTheUnwrappedSpy() throws Exception {
        try (var connection = dataSource.getConnection()) { assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:analysis-preparation"); }
        target = AopTestUtils.getUltimateTargetObject(store); reset(target);
    }
    @Test void ownerExpenseAndTrashScopeAndRawQueryOrderingArePreservedWithoutLedgerMutation() {
        long owner = fixture(); fixture(); long count = entries.count();
        var result = preparation.prepare(owner, plan(null));
        assertThat(result.expenseEntries()).hasSize(25); assertThat(result.topExpenses()).hasSize(20);
        assertThat(result.overview().expense()).isEqualByComparingTo("25000"); assertThat(result.overview().income()).isEqualByComparingTo("3000");
        assertThat(result.expenseEntries()).allSatisfy(row -> { assertThat(row.title()).startsWith("Expense " + owner + "-"); assertThat(row.memo()).isEmpty(); assertThat(row.categoryDetailName()).isEqualTo("Uncategorized"); });
        assertThat(result.expenseEntries().get(0).title()).isEqualTo("Expense " + owner + "-0");
        assertThat(result.topExpenses().get(0).title()).isEqualTo("Expense " + owner + "-24");
        assertThat(result.comparisonOverview()).isNull(); assertThat(entries.count()).isEqualTo(count); verifyNoInteractions(remote, redis, storage, coordinator);
    }
    @Test void weeklyDigestHasFullExpensesAndComparisonButDoesNotIssueTheTopExpenseQuery() {
        long owner = fixture();
        var calendar = LedgerAnalysisCalendarPolicy.resolvePlan(new Draft(LedgerAiAnalysisMode.COMPARISON, null, LedgerAiComparisonPreset.WEEKLY_DIGEST, LocalDate.of(2026, 1, 12), null, null, null, null), null);
        var result = preparation.prepare(owner, calendar);
        assertThat(result.expenseEntries()).hasSize(25); assertThat(result.topExpenses()).isEmpty(); assertThat(result.comparisonOverview().expense()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(target, never()).topExpenses(any(), any(), anyInt()); verifyNoInteractions(remote);
    }
    @Test void noNewEncompassingTransactionIsOpenedAroundTheStatisticsAndProjectionQueries() {
        long owner = fixture();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var result = call.callRealMethod(); assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return result;
        }).when(target).overview(eq(owner), any());
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return call.callRealMethod(); }).when(target).expenses(eq(owner), any());
        preparation.prepare(owner, plan(null));
        verify(target).overview(owner, primary); verify(target).expenses(owner, primary);
    }
    @Test void anExistingCallerTransactionIsJoinedRatherThanSuspendedOrReplaced() {
        long owner = fixture();
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse(); return call.callRealMethod(); }).when(target).overview(eq(owner), any());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> preparation.prepare(owner, plan(null)));
    }
    @Test void inactiveOwnerStopsAtTheFirstStatisticsLookupWithoutOtherSourceReads() {
        long owner = fixture(); new TransactionTemplate(transactions).executeWithoutResult(tx -> users.findById(owner).orElseThrow().setActive(false));
        assertThatThrownBy(() -> preparation.prepare(owner, plan(null))).hasMessage("사용자 계정을 찾을 수 없습니다.");
        verify(target, never()).categories(any(), any()); verify(target, never()).expenses(any(), any()); verifyNoInteractions(remote, redis, storage, coordinator);
    }
    private AnalysisPlan plan(LedgerAiComparisonPreset preset) { return new AnalysisPlan(LedgerAiAnalysisMode.PERIOD, LedgerAiAnalysisPeriod.WEEK, preset, primary, null); }
    private long fixture() {
        return new TransactionTemplate(transactions).execute(tx -> {
            var u = new AppUser(); u.setLoginId("analysis-prep-" + UUID.randomUUID()); u.setDisplayName("Fixture"); u.setPasswordHash("test-only-hash"); users.save(u);
            var group = new CategoryGroup(); group.setOwner(u); group.setName("Expense"); group.setEntryType(EntryType.EXPENSE); groups.save(group);
            var income = new CategoryGroup(); income.setOwner(u); income.setName("Income"); income.setEntryType(EntryType.INCOME); groups.save(income);
            var payment = new PaymentMethod(); payment.setOwner(u); payment.setName("Card"); payment.setKind(PaymentMethodKind.CARD); payments.save(payment);
            for (int i = 0; i < 27; i++) {
                var e = new LedgerEntry(); e.setOwner(u); e.setEntryDate(LocalDate.of(2026, 1, 6)); e.setTitle("Expense " + u.getId() + "-" + i); e.setAmount(new BigDecimal(i == 26 ? "3000" : "1000"));
                e.setEntryType(i == 26 ? EntryType.INCOME : EntryType.EXPENSE); e.setCategoryGroup(i == 26 ? income : group); e.setPaymentMethod(payment);
                if (i == 25) e.setDeletedAt(LocalDateTime.of(2026, 1, 7, 0, 0)); entries.save(e);
            }
            return u.getId();
        });
    }
}
