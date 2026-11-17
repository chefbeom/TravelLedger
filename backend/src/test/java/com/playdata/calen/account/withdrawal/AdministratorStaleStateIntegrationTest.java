package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.account-withdrawal.internal-workflow-enabled=true",
        "spring.datasource.url=jdbc:h2:mem:admin-stale-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class AdministratorStaleStateIntegrationTest {
    @Autowired AppUserService accounts;
    @Autowired AppUserRepository users;
    @Autowired AdministratorWithdrawalGuard guard;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test void aCachedAdministratorWhoWasDisabledWhileWaitingDoesNotCountAsAnActiveSuccessor() throws Exception {
        AppUser departing = admin(), other = admin();
        var pool = Executors.newFixedThreadPool(2);
        var preloaded = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var otherLocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var check = pool.submit(() -> {
                var requestEntities = entityManagerFactory.createEntityManager();
                TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntities));
                try {
                    AppUser stale = requestEntities.find(AppUser.class, other.getId());
                    preloaded.countDown();
                    await(proceed);
                    assertThat(stale.isActive()).isTrue();
                    var tx = new TransactionTemplate(transactions);
                    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                    return tx.execute(status -> guard.lockAndCheckIfAdministrator(departing.getId()));
                } finally {
                    TransactionSynchronizationManager.unbindResource(entityManagerFactory);
                    requestEntities.close();
                }
            });
            assertThat(preloaded.await(5, TimeUnit.SECONDS)).isTrue();
            var disable = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser current = users.lockForAccountSecurityUpdate(other.getId()).orElseThrow();
                current.setActive(false);
                users.saveAndFlush(current);
                otherLocked.countDown();
                await(release);
            }));
            assertThat(otherLocked.await(5, TimeUnit.SECONDS)).isTrue();
            proceed.countDown();
            assertThatThrownBy(() -> check.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            disable.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> check.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadRequestException.class)
                    .cause().hasMessageContaining("마지막 관리자");
            assertThat(users.findById(departing.getId()).orElseThrow().isActive()).isTrue();
            assertThat(users.countByRoleAndActiveTrue(AppUserRole.ADMIN)).isEqualTo(1);
        } finally {
            proceed.countDown(); release.countDown(); pool.shutdownNow();
        }
    }

    private AppUser admin() {
        return accounts.registerUser("a" + UUID.randomUUID().toString().replace("-", ""),
                "관리자", "test-password", "12345678", AppUserRole.ADMIN);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }
}
