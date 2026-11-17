package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.ocr.LedgerOcrCategoryWriteFence;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:classification-withdrawal-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class LedgerClassificationWithdrawalConcurrencyTest {
    @Autowired AppUserRepository users;
    @Autowired LedgerClassificationRuleService classification;
    @Autowired CategoryService categories;
    @Autowired PaymentMethodService payments;
    @Autowired LedgerOcrCategoryWriteFence ocrCategories;
    @Autowired PlatformTransactionManager transactions;

    @Test void writesWaitingForDeactivationAreRejectedBeforeTheyCanRecreateRules() throws Exception {
        AppUser owner = new AppUser();
        owner.setLoginId("classification_" + UUID.randomUUID());
        owner.setDisplayName("분류 사용자");
        owner.setActive(true);
        Long ownerId = users.saveAndFlush(owner).getId();
        var pool = Executors.newFixedThreadPool(5);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser managed = users.lockForAccountSecurityUpdate(ownerId).orElseThrow();
                managed.setActive(false);
                users.saveAndFlush(managed);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("deactivation timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            // Null requests/IDs verify that account locking happens before any parsing or repository writes.
            List<Callable<Object>> operations = List.of(
                    () -> classification.createRule(ownerId, null),
                    () -> classification.approveRecommendedRule(ownerId, null),
                    () -> classification.updateRule(ownerId, null, null),
                    () -> classification.deactivateRule(ownerId, null));
            var writes = operations.stream().map(pool::submit).toList();
            for (var write : writes) {
                assertThatThrownBy(() -> write.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            }
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            for (var write : writes) {
                assertThatThrownBy(() -> write.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(NotFoundException.class);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void catalogWritesAndOcrWaitForDeactivationAndCannotRecreateData() throws Exception {
        AppUser owner = new AppUser();
        owner.setLoginId("catalog_" + UUID.randomUUID());
        owner.setDisplayName("카탈로그 사용자");
        Long ownerId = users.saveAndFlush(owner).getId();
        var pool = Executors.newFixedThreadPool(17);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ranOcrWrite = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var managed = users.lockForAccountSecurityUpdate(ownerId).orElseThrow();
                managed.setActive(false);
                users.saveAndFlush(managed);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("deactivation timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            List<Callable<Object>> operations = List.of(
                    () -> categories.reorderGroups(ownerId, null),
                    () -> categories.reorderDetails(ownerId, null, null),
                    () -> categories.createGroup(ownerId, null),
                    () -> categories.createDetail(ownerId, null),
                    () -> { categories.deactivateGroup(ownerId, null); return null; },
                    () -> categories.activateGroup(ownerId, null),
                    () -> { categories.deactivateDetail(ownerId, null); return null; },
                    () -> categories.activateDetail(ownerId, null),
                    () -> { categories.deleteGroup(ownerId, null, null); return null; },
                    () -> { categories.deleteDetail(ownerId, null, null); return null; },
                    () -> payments.reorder(ownerId, null),
                    () -> payments.create(ownerId, null),
                    () -> { payments.deactivate(ownerId, null); return null; },
                    () -> payments.activate(ownerId, null),
                    () -> { payments.delete(ownerId, null, null); return null; },
                    () -> ocrCategories.withActiveOwner(ownerId, () -> { ranOcrWrite.set(true); return null; }));
            var writes = operations.stream().map(pool::submit).toList();
            for (var write : writes) {
                assertThatThrownBy(() -> write.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            }
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            for (var write : writes) {
                assertThatThrownBy(() -> write.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(NotFoundException.class);
            }
            assertThat(ranOcrWrite).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
