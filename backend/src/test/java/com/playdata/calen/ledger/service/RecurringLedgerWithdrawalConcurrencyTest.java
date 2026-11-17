package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.RecurringLedgerMode;
import com.playdata.calen.ledger.domain.RecurringLedgerRule;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.RecurringLedgerOccurrenceRepository;
import com.playdata.calen.ledger.repository.RecurringLedgerRuleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.ledger.recurring.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:recurring-withdrawal-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class RecurringLedgerWithdrawalConcurrencyTest {
    @Autowired AppUserRepository users;
    @Autowired CategoryGroupRepository categories;
    @Autowired RecurringLedgerRuleRepository rules;
    @Autowired RecurringLedgerOccurrenceRepository occurrences;
    @Autowired RecurringLedgerService recurring;
    @Autowired PlatformTransactionManager transactions;

    @Test void inactiveOwnerIsSkippedAndProcessingResumesAfterReactivation() {
        LocalDate due = LocalDate.of(2033, 5, 15);
        Fixture f = fixture(due);
        AppUser owner = users.findById(f.ownerId()).orElseThrow();
        owner.setActive(false);
        users.saveAndFlush(owner);
        assertThat(recurring.processDueDate(due)).isZero();
        assertThat(occurrences.findByRuleIdAndScheduledDate(f.ruleId(), due)).isEmpty();
        owner = users.findById(f.ownerId()).orElseThrow();
        owner.setActive(true);
        users.saveAndFlush(owner);
        assertThat(recurring.processDueDate(due)).isEqualTo(1);
        assertThat(occurrences.findByRuleIdAndScheduledDate(f.ruleId(), due)).isPresent();
    }

    @Test void schedulerWaitingOnAccountDeactivationDoesNotCreateAnOccurrence() throws Exception {
        LocalDate due = LocalDate.of(2034, 7, 19);
        Fixture f = fixture(due);
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser owner = users.lockForAccountSecurityUpdate(f.ownerId()).orElseThrow();
                owner.setActive(false);
                users.saveAndFlush(owner);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("deactivation timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var scheduler = pool.submit(() -> recurring.processDueDate(due));
            assertThatThrownBy(() -> scheduler.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            assertThat(scheduler.get(5, TimeUnit.SECONDS)).isZero();
            assertThat(occurrences.findByRuleIdAndScheduledDate(f.ruleId(), due)).isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void ruleCreationWaitingOnDeactivationCannotWriteAfterward() throws Exception {
        Fixture f = fixture(LocalDate.of(2035, 8, 19));
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser owner = users.lockForAccountSecurityUpdate(f.ownerId()).orElseThrow();
                owner.setActive(false);
                users.saveAndFlush(owner);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("deactivation timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            // A null request is intentional: active-owner validation must run before parsing or writing.
            var create = pool.submit(() -> recurring.createRule(f.ownerId(), null));
            assertThatThrownBy(() -> create.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> create.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThat(rules.findAllByOwnerIdOrderByActiveDescDayOfMonthAscIdAsc(f.ownerId())).hasSize(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private Fixture fixture(LocalDate due) {
        return new TransactionTemplate(transactions).execute(status -> {
            AppUser owner = new AppUser();
            owner.setLoginId("recurring_" + UUID.randomUUID());
            owner.setDisplayName("정기 거래 사용자");
            users.saveAndFlush(owner);
            CategoryGroup category = new CategoryGroup();
            category.setOwner(owner);
            category.setName("정기 지출");
            category.setEntryType(EntryType.EXPENSE);
            categories.saveAndFlush(category);
            RecurringLedgerRule rule = new RecurringLedgerRule();
            rule.setOwner(owner);
            rule.setTitle("정기 결제");
            rule.setAmount(BigDecimal.valueOf(1000));
            rule.setEntryType(EntryType.EXPENSE);
            rule.setDayOfMonth(due.getDayOfMonth());
            rule.setMonthInterval(1);
            rule.setStartDate(due.minusMonths(1));
            rule.setMode(RecurringLedgerMode.CONFIRM);
            rule.setCategoryGroup(category);
            rules.saveAndFlush(rule);
            return new Fixture(owner.getId(), rule.getId());
        });
    }

    private record Fixture(Long ownerId, Long ruleId) {}
}
