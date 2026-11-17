package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.account-withdrawal.internal-workflow-enabled=true",
        "app.security.withdrawal-task-seal-key=test-withdrawal-secret-at-least-32-characters",
        "spring.datasource.url=jdbc:h2:mem:admin-withdrawal-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class AdministratorWithdrawalGuardTest {
    @Autowired AppUserService appUsers;
    @Autowired AppUserRepository users;
    @Autowired AccountWithdrawalStartService start;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired DataSource dataSource;

    @Test void simultaneousAdministratorsCannotBothWithdrawAndLeaveNoActiveAdministrator() throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261006_032__ledger_embedding_sync_jobs.sql"))
                .execute(dataSource);
        AppUser first = administrator();
        assertThatThrownBy(() -> start.start(first.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("마지막 관리자");
        assertThat(users.findById(first.getId()).orElseThrow().isActive()).isTrue();
        AppUser second = administrator();
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try {
            var one = pool.submit(() -> { ready.countDown(); release.await(); return start.start(first.getId(), "12345678", Map.of(), Map.of()); });
            var two = pool.submit(() -> { ready.countDown(); release.await(); return start.start(second.getId(), "12345678", Map.of(), Map.of()); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); release.countDown();
            int succeeded = 0, rejected = 0;
            for (var future : List.of(one, two)) {
                try { assertThat(future.get(15, TimeUnit.SECONDS)).isNotNull(); succeeded++; }
                catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(BadRequestException.class).hasMessageContaining("마지막 관리자");
                    rejected++;
                }
            }
            assertThat(succeeded).isEqualTo(1); assertThat(rejected).isEqualTo(1);
            assertThat(users.countByRoleAndActiveTrue(AppUserRole.ADMIN)).isEqualTo(1);
            assertThat(requests.count()).isEqualTo(1);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    private AppUser administrator() {
        return appUsers.registerUser("admin" + UUID.randomUUID().toString().replace("-", ""),
                "관리자", "test-password", "12345678", AppUserRole.ADMIN);
    }
}
