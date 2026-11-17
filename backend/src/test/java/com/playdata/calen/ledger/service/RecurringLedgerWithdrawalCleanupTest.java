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
import com.playdata.calen.ledger.domain.RecurringLedgerMode;
import com.playdata.calen.ledger.domain.RecurringLedgerOccurrence;
import com.playdata.calen.ledger.domain.RecurringLedgerOccurrenceStatus;
import com.playdata.calen.ledger.domain.RecurringLedgerRule;
import com.playdata.calen.ledger.embedding.LedgerEmbeddingRecordType;
import com.playdata.calen.ledger.embedding.sync.LedgerEmbeddingSyncStore;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.RecurringLedgerOccurrenceRepository;
import com.playdata.calen.ledger.repository.RecurringLedgerRuleRepository;
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
        "spring.datasource.url=jdbc:h2:mem:recurring-cleanup-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class RecurringLedgerWithdrawalCleanupTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired CategoryGroupRepository categories;
    @Autowired RecurringLedgerRuleRepository rules;
    @Autowired RecurringLedgerOccurrenceRepository occurrences;
    @Autowired RecurringLedgerWithdrawalCleanup cleanup;
    @Autowired LedgerEmbeddingSyncStore sync;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void createEmbeddingOutboxSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261006_032__ledger_embedding_sync_jobs.sql"))
                .execute(dataSource);
    }

    @Test void removesOnlyDepartingOwnersRulesAndQueuesTombstonesWithCaptureDisabled() {
        Fixture owner = fixture(true, true), other = fixture(false, false);
        long secondRule = new TransactionTemplate(transactions).execute(status -> {
            var category = categories.findById(owner.categoryId()).orElseThrow();
            return rule(users.findById(owner.userId()).orElseThrow(), category, false).getId();
        });

        new TransactionTemplate(transactions).executeWithoutResult(status -> cleanup.removeRulesAndOccurrences(owner.userId()));

        assertThat(rules.findById(owner.ruleId())).isEmpty();
        assertThat(rules.findById(secondRule)).isEmpty();
        assertThat(occurrences.findById(owner.occurrenceId())).isEmpty();
        assertThat(rules.findById(other.ruleId())).isPresent();
        assertThat(occurrences.findById(other.occurrenceId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from ledger_embedding_sync_jobs where owner_id = ? and record_type = 'recurring_rule' and state = 'PENDING'", Integer.class, owner.userId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from ledger_embedding_sync_jobs where owner_id = ?", Integer.class, other.userId())).isZero();
        new TransactionTemplate(transactions).executeWithoutResult(status -> cleanup.removeRulesAndOccurrences(owner.userId()));
        assertThat(jdbc.queryForObject("select count(*) from ledger_embedding_sync_jobs where owner_id = ?", Integer.class, owner.userId())).isEqualTo(2);
    }

    @Test void staleEmbeddingUpsertClaimIsSupersededByDeletion() {
        Fixture owner = fixture(true, true);
        jdbc.update("""
                insert into ledger_embedding_sync_jobs(owner_id, record_type, source_id, revision, applied_revision,
                    state, attempts, claim_token, claimed_revision, lease_until, next_attempt_at, created_at, updated_at)
                values (?, 'recurring_rule', ?, 2, 1, 'PROCESSING', 1, 'test-claim', 2,
                    timestampadd(minute, 10, current_timestamp), current_timestamp, current_timestamp, current_timestamp)
                """, owner.userId(), owner.ruleId());
        new TransactionTemplate(transactions).executeWithoutResult(status -> cleanup.removeRulesAndOccurrences(owner.userId()));
        var staleClaim = new LedgerEmbeddingSyncStore.Claim(owner.userId(), LedgerEmbeddingRecordType.RECURRING_RULE,
                owner.ruleId(), 2, "test-claim", 1);
        assertThat(sync.prepareLatest(staleClaim)).isNull();
        assertThat(jdbc.queryForObject("select state from ledger_embedding_sync_jobs where owner_id = ? and source_id = ?", String.class, owner.userId(), owner.ruleId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select revision from ledger_embedding_sync_jobs where owner_id = ? and source_id = ?", Long.class, owner.userId(), owner.ruleId())).isEqualTo(4L);
    }

    @Test void failedTransactionRollsBackBothDeletesAndTombstone() {
        Fixture owner = fixture(true, true);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            cleanup.removeRulesAndOccurrences(owner.userId());
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(rules.findById(owner.ruleId())).isPresent();
        assertThat(occurrences.findById(owner.occurrenceId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from ledger_embedding_sync_jobs where owner_id = ?", Integer.class, owner.userId())).isZero();
    }

    @Test void refusesActiveOrMerelyDisabledAccounts() {
        Fixture activeWithRequest = fixture(false, true), disabledWithoutRequest = fixture(true, false);
        for (long userId : new long[]{activeWithRequest.userId(), disabledWithoutRequest.userId()}) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(
                    status -> cleanup.removeRulesAndOccurrences(userId))).isInstanceOf(BadRequestException.class);
        }
        assertThat(rules.findById(activeWithRequest.ruleId())).isPresent();
        assertThat(rules.findById(disabledWithoutRequest.ruleId())).isPresent();
    }

    private Fixture fixture(boolean inactive, boolean pending) {
        return new TransactionTemplate(transactions).execute(status -> {
            var user = new AppUser();
            user.setLoginId("cleanup_" + UUID.randomUUID());
            user.setDisplayName("정기 거래 사용자");
            user.setActive(!inactive);
            users.saveAndFlush(user);
            var category = new CategoryGroup();
            category.setOwner(user); category.setName("정기 지출"); category.setEntryType(EntryType.EXPENSE);
            categories.saveAndFlush(category);
            var rule = rule(user, category, true);
            var occurrence = new RecurringLedgerOccurrence();
            occurrence.setRule(rule); occurrence.setScheduledDate(LocalDate.of(2034, 7, 19));
            occurrence.setMode(RecurringLedgerMode.CONFIRM); occurrence.setStatus(RecurringLedgerOccurrenceStatus.PENDING);
            occurrences.saveAndFlush(occurrence);
            if (pending) {
                var request = new AccountWithdrawalRequest();
                request.setId(UUID.randomUUID().toString()); request.setUserId(user.getId());
                request.setState(AccountWithdrawalRequest.State.IN_PROGRESS);
                request.setRequestedAt(LocalDateTime.now()); request.setUpdatedAt(LocalDateTime.now());
                requests.saveAndFlush(request);
            }
            return new Fixture(user.getId(), category.getId(), rule.getId(), occurrence.getId());
        });
    }

    private RecurringLedgerRule rule(AppUser user, CategoryGroup category, boolean active) {
        var rule = new RecurringLedgerRule();
        rule.setOwner(user); rule.setTitle("정기 결제"); rule.setAmount(BigDecimal.valueOf(1000));
        rule.setEntryType(EntryType.EXPENSE); rule.setDayOfMonth(19); rule.setMonthInterval(1);
        rule.setStartDate(LocalDate.of(2034, 6, 19)); rule.setMode(RecurringLedgerMode.CONFIRM);
        rule.setCategoryGroup(category); rule.setActive(active);
        return rules.saveAndFlush(rule);
    }

    private record Fixture(long userId, long categoryId, long ruleId, long occurrenceId) {}
}
