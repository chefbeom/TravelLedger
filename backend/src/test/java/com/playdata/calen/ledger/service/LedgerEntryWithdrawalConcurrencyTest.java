package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ledger-write-withdrawal-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class LedgerEntryWithdrawalConcurrencyTest {
    @Autowired AppUserService appUsers;
    @Autowired AppUserRepository users;
    @Autowired LedgerEntryService entries;
    @Autowired LedgerExcelImportService imports;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @Test void inactiveAccountCannotCreateChangeOrRestoreTransactions() {
        AppUser user = user(); user.setActive(false); users.saveAndFlush(user);
        long id = user.getId();
        for (Runnable mutation : List.<Runnable>of(
                () -> entries.create(id, null), () -> entries.update(id, 1L, null),
                () -> entries.bulkUpdate(id, null), () -> entries.delete(id, 1L, false),
                () -> entries.restore(id, 1L), () -> entries.emptyTrash(id),
                () -> entries.restoreChangeHistory(id, 1L), () -> imports.importRows(id, null))) {
            assertThatThrownBy(mutation::run).isInstanceOf(NotFoundException.class);
        }
    }

    @Test void transactionAndImportWaitingOnDeactivationCannotWriteAfterward() throws Exception {
        AppUser user = user();
        var pool = Executors.newFixedThreadPool(3);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser owner = users.lockForAccountSecurityUpdate(user.getId()).orElseThrow();
                owner.setActive(false); users.saveAndFlush(owner); locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("deactivation timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            // Invalid payloads intentionally prove the owner lock is checked before parsing or writes.
            var create = pool.submit(() -> entries.create(user.getId(), null));
            var importing = pool.submit(() -> imports.importRows(user.getId(), null));
            assertThatThrownBy(() -> create.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> importing.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); deactivate.get(5, TimeUnit.SECONDS);
            for (var work : List.of(create, importing)) {
                assertThatThrownBy(() -> work.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(NotFoundException.class);
            }
            assertThat(jdbc.queryForObject("select count(*) from ledger_entries where owner_id = ?", Integer.class, user.getId())).isZero();
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    private AppUser user() {
        return appUsers.registerUser("ledger" + UUID.randomUUID().toString().replace("-", ""),
                "가계부 사용자", "test-password", "12345678");
    }
}
