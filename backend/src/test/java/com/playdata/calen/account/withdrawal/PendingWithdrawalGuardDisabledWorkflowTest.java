package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.LoginAuditLogRepository;
import com.playdata.calen.account.service.AdminService;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.LoginAuditLogService;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.drive.service.DriveAdminService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.account-withdrawal.internal-workflow-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:withdrawal-disabled-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class PendingWithdrawalGuardDisabledWorkflowTest {
    @Autowired AppUserService appUsers;
    @Autowired AppUserRepository users;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired AdminService admins;
    @Autowired DriveAdminService driveAdmins;
    @Autowired LoginAuditLogService audit;
    @Autowired LoginAuditLogRepository logs;

    @Test void disabledWorkflowStillProtectsPreviouslyStartedWithdrawal() {
        var user = appUsers.registerUser("guard" + UUID.randomUUID().toString().replace("-", ""),
                "테스트", "test-password", "12345678");
        user.setActive(false);
        users.saveAndFlush(user);
        var request = new AccountWithdrawalRequest();
        request.setId(UUID.randomUUID().toString());
        request.setUserId(user.getId());
        request.setState(AccountWithdrawalRequest.State.IN_PROGRESS);
        request.setRequestedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now());
        requests.saveAndFlush(request);

        var previousIds = logs.findAll().stream().map(log -> log.getId()).collect(java.util.stream.Collectors.toSet());
        audit.record(user.getLoginId(), "192.0.2.99", "late-agent", LoginAuditStatus.BAD_CREDENTIALS, "failure", null);
        var late = logs.findAll().stream().filter(log -> !previousIds.contains(log.getId())).findFirst().orElseThrow();
        assertThat(late.getLoginId()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
        assertThat(late.getClientIp()).isEqualTo(AuditLogAnonymization.ANONYMOUS);
        assertThat(late.getUserAgent()).isNull();

        assertThatThrownBy(() -> admins.updateUserActive(-1L, user.getId(), true))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("탈퇴 처리 중");
        assertThatThrownBy(() -> driveAdmins.updateUserStatus(true, user.getId(), true))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("탈퇴 처리 중");
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test void disabledUserWithoutWithdrawalCanStillBeReactivated() {
        var user = appUsers.registerUser("guard" + UUID.randomUUID().toString().replace("-", ""),
                "테스트", "test-password", "12345678");
        user.setActive(false);
        users.saveAndFlush(user);
        admins.updateUserActive(-1L, user.getId(), true);
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isTrue();
        driveAdmins.updateUserStatus(true, user.getId(), false);
        driveAdmins.updateUserStatus(true, user.getId(), true);
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isTrue();
    }
}
