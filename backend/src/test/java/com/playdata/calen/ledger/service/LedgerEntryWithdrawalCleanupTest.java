package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequest;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequestRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.LedgerEntryChangeAction;
import com.playdata.calen.ledger.domain.LedgerEntryChangeHistory;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryChangeHistoryRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.account-withdrawal.internal-workflow-enabled=true",
        "app.embedding.sync.capture-enabled=false", "app.embedding.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:entry-cleanup-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class LedgerEntryWithdrawalCleanupTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired CategoryGroupRepository categories;
    @Autowired PaymentMethodRepository payments;
    @Autowired LedgerEntryRepository entries;
    @Autowired LedgerEntryChangeHistoryRepository histories;
    @Autowired LedgerEntryWithdrawalCleanup cleanup;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void createEmbeddingOutboxSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261006_032__ledger_embedding_sync_jobs.sql"))
                .execute(dataSource);
    }

    @Test void deletesOnlyOwnersEntriesIncludingTrashAndReschedulesKnownOldVectors() {
        Fixture owner = fixture(true, true), other = fixture(false, false);
        jdbc.update("""
                insert into ledger_embedding_sync_jobs(owner_id, record_type, source_id, revision, applied_revision,
                    state, attempts, next_attempt_at, last_error_code, created_at, updated_at)
                values (?, 'ledger_entry', 999999999, 1, 0, 'FAILED', 8,
                    current_timestamp, 'HTTP_401', current_timestamp, current_timestamp)
                """, owner.userId());
        runCleanup(owner.userId());
        assertThat(entries.findById(owner.entryId())).isEmpty();
        assertThat(entries.findById(owner.trashedId())).isEmpty();
        assertThat(histories.findById(owner.historyId())).isEmpty();
        assertThat(entries.findById(other.entryId())).isPresent();
        assertThat(entries.findById(other.trashedId())).isPresent();
        assertThat(histories.findById(other.historyId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from ledger_embedding_sync_jobs where owner_id = ? and record_type = 'ledger_entry' and state = 'PENDING'", Integer.class, owner.userId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from ledger_embedding_sync_jobs where owner_id = ?", Integer.class, other.userId())).isZero();
        assertThat(jdbc.queryForObject("select last_error_code from ledger_embedding_sync_jobs where owner_id = ? and source_id = 999999999", String.class, owner.userId())).isNull();
        runCleanup(owner.userId());
        assertThat(jdbc.queryForObject("select count(*) from ledger_embedding_sync_jobs where owner_id = ?", Integer.class, owner.userId())).isEqualTo(3);
    }

    @Test void exceptionRollsBackEntriesHistoriesAndTombstonesTogether() {
        Fixture owner = fixture(true, true);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            cleanup.removeEntriesAndHistories(owner.userId());
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(entries.findById(owner.entryId())).isPresent();
        assertThat(entries.findById(owner.trashedId())).isPresent();
        assertThat(histories.findById(owner.historyId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from ledger_embedding_sync_jobs where owner_id = ?", Integer.class, owner.userId())).isZero();
    }

    @Test void activeAndMerelyDisabledAccountsCannotBeCleaned() {
        Fixture active = fixture(false, true), disabled = fixture(true, false);
        assertThatThrownBy(() -> runCleanup(active.userId())).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> runCleanup(disabled.userId())).isInstanceOf(BadRequestException.class);
        assertThat(entries.findById(active.entryId())).isPresent();
        assertThat(entries.findById(disabled.entryId())).isPresent();
    }

    private void runCleanup(long userId) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> cleanup.removeEntriesAndHistories(userId));
    }

    private Fixture fixture(boolean inactive, boolean pending) {
        return new TransactionTemplate(transactions).execute(status -> {
            var user = new AppUser(); user.setLoginId("entry_" + UUID.randomUUID());
            user.setDisplayName("가계부 사용자"); user.setActive(!inactive); users.saveAndFlush(user);
            var category = new CategoryGroup(); category.setOwner(user); category.setName("식비");
            category.setEntryType(EntryType.EXPENSE); categories.saveAndFlush(category);
            var payment = new PaymentMethod(); payment.setOwner(user); payment.setName("현금");
            payment.setKind(PaymentMethodKind.CASH); payments.saveAndFlush(payment);
            var entry = entry(user, category, payment, false);
            var trashed = entry(user, category, payment, true);
            var history = new LedgerEntryChangeHistory(); history.setOwner(user); history.setAction(LedgerEntryChangeAction.UPDATE);
            history.setCreatedAt(LocalDateTime.now()); history.setEntryCount(1); history.setSummary("변경");
            history.setBeforeSnapshotJson("[]"); history.setAfterSnapshotJson("[]"); histories.saveAndFlush(history);
            if (pending) {
                var request = new AccountWithdrawalRequest(); request.setId(UUID.randomUUID().toString());
                request.setUserId(user.getId()); request.setState(AccountWithdrawalRequest.State.IN_PROGRESS);
                request.setRequestedAt(LocalDateTime.now()); request.setUpdatedAt(LocalDateTime.now()); requests.saveAndFlush(request);
            }
            return new Fixture(user.getId(), entry.getId(), trashed.getId(), history.getId());
        });
    }

    private LedgerEntry entry(AppUser owner, CategoryGroup category, PaymentMethod payment, boolean trashed) {
        var entry = new LedgerEntry(); entry.setOwner(owner); entry.setCategoryGroup(category); entry.setPaymentMethod(payment);
        entry.setEntryDate(LocalDate.of(2034, 7, 19)); entry.setTitle("거래"); entry.setMemo("개인 메모");
        entry.setAmount(BigDecimal.valueOf(1000)); entry.setEntryType(EntryType.EXPENSE);
        if (trashed) entry.setDeletedAt(LocalDateTime.now());
        return entries.saveAndFlush(entry);
    }

    private record Fixture(long userId, long entryId, long trashedId, long historyId) {}
}
