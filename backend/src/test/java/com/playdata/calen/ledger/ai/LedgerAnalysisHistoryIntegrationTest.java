package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.ledger.adapter.out.persistence.LedgerAnalysisHistoryQueryAdapter;
import com.playdata.calen.ledger.adapter.out.serialization.LedgerAnalysisHistoryJsonAdapter;
import com.playdata.calen.ledger.application.port.in.ManageLedgerAnalysisHistoryUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.*;
import com.playdata.calen.ledger.repository.*;
import com.playdata.calen.travel.service.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
        "app.seed.enabled=false", "spring.datasource.url=jdbc:h2:mem:analysis-histories;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LedgerAnalysisHistoryIntegrationTest {
    @Autowired ManageLedgerAnalysisHistoryUseCase history;
    @Autowired AppUserRepository users;
    @Autowired LedgerAiAnalysisHistoryRepository rows;
    @Autowired LedgerEntryRepository entries;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    @MockitoSpyBean LedgerAnalysisHistoryQueryAdapter store;
    @MockitoSpyBean LedgerAnalysisHistoryJsonAdapter codec;
    @MockitoBean LedgerSpendingAnalysisPort remote;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean TravelClusterRefreshCoordinator coordinator;
    LedgerAnalysisHistoryQueryAdapter target;
    LedgerAnalysisHistoryJsonAdapter jsonTarget;
    final LocalDateTime created = LocalDateTime.of(2026, 1, 2, 12, 0);

    @BeforeEach void verifyIsolatedDatabaseAndResetOnlyTheUnwrappedSpies() throws Exception {
        try (var connection = dataSource.getConnection()) { assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:analysis-histories"); }
        target = AopTestUtils.getUltimateTargetObject(store); jsonTarget = AopTestUtils.getUltimateTargetObject(codec);
        reset(target, jsonTarget);
    }

    @Test void searchIsOwnerScopedInclusiveAndReturnsExistingSortAndPaginationMetadata() {
        long owner = user(), other = user();
        var first = row(owner, LedgerAiAnalysisStatus.COMPLETED, created, null, "{}");
        var last = row(owner, LedgerAiAnalysisStatus.FAILED, created, null, null);
        row(other, LedgerAiAnalysisStatus.COMPLETED, created, null, "{}");
        row(owner, LedgerAiAnalysisStatus.PROCESSING, created.plusDays(1), null, null);
        var page = history.search(owner, new Filter(null, null, created.toLocalDate(), created.toLocalDate(), false, -2, 500));
        assertThat(page.page()).isZero(); assertThat(page.size()).isEqualTo(50); assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(Summary::id).containsExactly(last.getId(), first.getId());
        verifyNoInteractions(jsonTarget, remote, redis, storage, coordinator);
    }
    @Test void wrongOwnerCannotReadOrDeleteAndProcessingResultsAreNeverDecoded() {
        long owner = user(), other = user(); var row = row(owner, LedgerAiAnalysisStatus.PROCESSING, created, null, "{}");
        assertThatThrownBy(() -> history.detail(other, row.getId())).hasMessage("AI 분석 이력을 찾을 수 없습니다.");
        assertThatThrownBy(() -> history.delete(other, row.getId())).hasMessage("AI analysis history was not found.");
        assertThat(history.detail(owner, row.getId()).result()).isNull(); assertThat(rows.existsById(row.getId())).isTrue();
        verifyNoInteractions(jsonTarget, remote);
    }
    @Test void malformedCompletedHistoryIsVisibleButNotReusableAndNeverMutatesTheLedger() {
        long owner = user(); var row = row(owner, LedgerAiAnalysisStatus.COMPLETED, created, null, "broken"); long count = entries.count();
        var detail = history.detail(owner, row.getId());
        assertThat(detail.result().totalExpense()).isZero(); assertThat(detail.result().summary()).isEqualTo(" raw summary ");
        assertThat(detail.result().warnings()).containsExactly("저장된 AI 분석 결과를 읽지 못했습니다. 재분석을 실행해 주세요.");
        assertThat(history.readReusable(history.completed(key(owner, created)).orElseThrow())).isNull();
        assertThat(entries.count()).isEqualTo(count); assertThat(rows.findById(row.getId()).orElseThrow().getStatus()).isEqualTo(LedgerAiAnalysisStatus.COMPLETED);
        verifyNoInteractions(remote, redis, storage, coordinator);
    }
    @Test void deletionJoinsAnOuterWriteTransactionAndRollsBackIncludingDeleteAll() {
        long owner = user(), other = user(); var first = row(owner, LedgerAiAnalysisStatus.PROCESSING, created, null, null);
        var second = row(owner, LedgerAiAnalysisStatus.FAILED, created, null, null); var foreign = row(other, LedgerAiAnalysisStatus.COMPLETED, created, null, "{}");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> { history.delete(owner, first.getId()); throw new IllegalStateException("rollback one"); })).hasMessage("rollback one");
        assertThat(rows.existsById(first.getId())).isTrue();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> { assertThat(history.deleteAll(owner)).isEqualTo(2); throw new IllegalStateException("rollback all"); })).hasMessage("rollback all");
        assertThat(rows.existsById(first.getId())).isTrue(); assertThat(rows.existsById(second.getId())).isTrue(); assertThat(rows.existsById(foreign.getId())).isTrue();
        assertThat(history.delete(owner, first.getId())).isEqualTo(1); assertThat(rows.existsById(first.getId())).isFalse();
    }
    @Test void crudHasOriginalRequiredTransactionsWhileInternalMatchingAndDecodingDoNotCreateOne() {
        long owner = user(); var row = row(owner, LedgerAiAnalysisStatus.COMPLETED, created, null, "{\"summary\":\"stored\"}");
        var readonly = new AtomicBoolean(true);
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isEqualTo(readonly.get()); return call.callRealMethod(); }).when(target).requireActiveOwner(owner);
        history.search(owner, new Filter(null, null, null, null, null, 0, 10)); history.detail(owner, row.getId());
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return call.callRealMethod(); }).when(target).completed(any());
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return call.callRealMethod(); }).when(jsonTarget).read(any());
        assertThat(history.readReusable(history.completed(key(owner, created)).orElseThrow()).summary()).isEqualTo("stored");
        readonly.set(false); history.delete(owner, row.getId());
    }
    @Test void matchingUsesExactProviderModelOwnerDatesAndInclusiveCutoffButNotComparisonPreset() {
        long owner = user(), other = user();
        row(owner, LedgerAiAnalysisStatus.COMPLETED, created.minusSeconds(1), LedgerAiComparisonPreset.CUSTOM, "{}");
        var first = row(owner, LedgerAiAnalysisStatus.COMPLETED, created, LedgerAiComparisonPreset.CUSTOM, "{}");
        var latest = row(owner, LedgerAiAnalysisStatus.COMPLETED, created, LedgerAiComparisonPreset.WEEKLY_DIGEST, "{}");
        row(other, LedgerAiAnalysisStatus.COMPLETED, created.plusSeconds(1), null, "{}");
        var processing = row(owner, LedgerAiAnalysisStatus.PROCESSING, created, null, null);
        var key = key(owner, created);
        assertThat(history.completed(key).orElseThrow().header().id()).isEqualTo(latest.getId()); assertThat(first.getId()).isLessThan(latest.getId());
        assertThat(history.processing(key).orElseThrow().header().id()).isEqualTo(processing.getId());
        assertThat(history.completed(new MatchKey(owner, "other-provider", key.model(), key.mode(), key.periodType(), key.from(), key.to(), null, null, created))).isEmpty();
        assertThat(history.completed(new MatchKey(owner, key.provider(), "other-model", key.mode(), key.periodType(), key.from(), key.to(), null, null, created))).isEmpty();
        assertThat(history.completed(key(owner, created.plusSeconds(1)))).isEmpty();
        assertThat(history.completed(new MatchKey(owner, key.provider(), key.model(), key.mode(), key.periodType(), key.from(), key.to(), key.from().minusMonths(1), key.to().minusMonths(1), null))).isEmpty();
    }
    @Test void inactiveOwnerFailsBeforeAnInvalidSearchOrPersistenceMutation() {
        long owner = user(); var row = row(owner, LedgerAiAnalysisStatus.FAILED, created, null, null);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> users.findById(owner).orElseThrow().setActive(false));
        assertThatThrownBy(() -> history.search(owner, new Filter(null, null, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), null, 0, 10))).hasMessage("사용자 계정을 찾을 수 없습니다.");
        assertThatThrownBy(() -> history.delete(owner, row.getId())).hasMessage("사용자 계정을 찾을 수 없습니다.");
        verify(target, never()).search(any(), any()); verify(target, never()).delete(any(), any()); assertThat(rows.existsById(row.getId())).isTrue();
    }
    private MatchKey key(long owner, LocalDateTime after) {
        return new MatchKey(owner, "provider", "model", LedgerAiAnalysisMode.PERIOD, LedgerAiAnalysisPeriod.MONTH,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, null, after);
    }
    private long user() {
        var owner = new AppUser(); owner.setLoginId("history-fixture-" + UUID.randomUUID()); owner.setDisplayName("Fixture"); owner.setPasswordHash("test-only-hash"); return users.saveAndFlush(owner).getId();
    }
    private LedgerAiAnalysisHistory row(long owner, LedgerAiAnalysisStatus status, LocalDateTime created, LedgerAiComparisonPreset preset, String json) {
        var row = new LedgerAiAnalysisHistory(); row.setOwner(users.findById(owner).orElseThrow()); row.setMode(LedgerAiAnalysisMode.PERIOD); row.setPeriodType(LedgerAiAnalysisPeriod.MONTH);
        row.setStatus(status); row.setFromDate(LocalDate.of(2026, 1, 1)); row.setToDate(LocalDate.of(2026, 1, 31)); row.setComparisonPreset(preset);
        row.setModel("model"); row.setProvider("provider"); row.setTitle(" raw title "); row.setSummary(" raw summary "); row.setCreatedAt(created); row.setResultJson(json); return rows.saveAndFlush(row);
    }
}
