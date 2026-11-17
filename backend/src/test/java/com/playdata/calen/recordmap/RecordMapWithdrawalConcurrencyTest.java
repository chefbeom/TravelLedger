package com.playdata.calen.recordmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.recordmap.application.port.in.WriteRecordMapUseCase;
import com.playdata.calen.recordmap.service.RecordMapMediaService;
import com.playdata.calen.recordmap.service.RecordMapShareService;
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
        "spring.datasource.url=jdbc:h2:mem:recordmap-withdrawal-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class RecordMapWithdrawalConcurrencyTest {
    @Autowired AppUserRepository users;
    @Autowired WriteRecordMapUseCase writes;
    @Autowired RecordMapMediaService media;
    @Autowired RecordMapShareService shares;
    @Autowired PlatformTransactionManager transactions;

    @Test void recordAndPhotoWritesWaitForAccountDeactivation() throws Exception {
        var user = new AppUser();
        user.setLoginId("recordmap-" + UUID.randomUUID());
        user.setDisplayName("기록 사용자");
        Long userId = users.saveAndFlush(user).getId();
        var pool = Executors.newFixedThreadPool(11);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.lockForAccountSecurityUpdate(userId).orElseThrow().setActive(false);
                users.flush();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            List<Callable<Object>> operations = List.of(
                    () -> writes.savePlace(userId, null, null),
                    () -> { writes.deletePlace(userId, null); return null; },
                    () -> writes.saveCollection(userId, null, null),
                    () -> { writes.deleteCollection(userId, null); return null; },
                    () -> writes.saveVisit(userId, null, null, null),
                    () -> { writes.deleteVisit(userId, null); return null; },
                    () -> writes.importTravel(userId, null),
                    () -> media.upload(userId, null, null),
                    () -> { media.delete(userId, null); return null; },
                    () -> { shares.revoke(userId, null); return null; });
            var work = operations.stream().map(pool::submit).toList();
            for (var item : work) {
                assertThatThrownBy(() -> item.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            }
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            for (var item : work) {
                assertThatThrownBy(() -> item.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(NotFoundException.class);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
