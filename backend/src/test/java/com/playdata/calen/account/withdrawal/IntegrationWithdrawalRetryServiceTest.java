package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.LoginAuditStatus;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.LoginAuditLogService;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.BadCredentialsException;

class IntegrationWithdrawalRetryServiceTest {
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AppUserService appUsers = mock(AppUserService.class);
    private final AccountWithdrawalRequestRepository requests = mock(AccountWithdrawalRequestRepository.class);
    private final WithdrawalCleanupTaskRepository tasks = mock(WithdrawalCleanupTaskRepository.class);
    private final LoginAuditLogService audit = mock(LoginAuditLogService.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IntegrationWithdrawalRetryService service = new IntegrationWithdrawalRetryService(
            users, appUsers, requests, tasks, audit, entities, jdbc);
    private final UUID requestId = UUID.randomUUID(), taskId = UUID.randomUUID();

    @Test void onlyKnownTransientFailuresWithPayloadCanBeRetried() {
        var task = task("KAKAO_UNLINK_UNCONFIRMED");
        assertThat(IntegrationWithdrawalRetryService.canRetry(task)).isTrue();
        task.setLastErrorCode("KAKAO_RECONNECT_UNVERIFIED");
        assertThat(IntegrationWithdrawalRetryService.canRetry(task)).isFalse();
        task.setLastErrorCode("KAKAO_UNLINK_UNCONFIRMED"); task.setEncryptedPayload(null);
        assertThat(IntegrationWithdrawalRetryService.canRetry(task)).isFalse();
        task.setKind(null);
        assertThat(IntegrationWithdrawalRetryService.canRetry(task)).isFalse();
    }

    @Test void freshAdminPinRequeuesOnlyTheSelectedRequestAndAuditsTheAction() {
        AppUser admin = user(1L, true, AppUserRole.ADMIN), owner = user(2L, false, AppUserRole.USER);
        var request = request(); var task = task("KAKAO_UNLINK_UNCONFIRMED");
        when(users.lockForAccountSecurityUpdate(1L)).thenReturn(Optional.of(admin));
        when(users.lockForAccountSecurityUpdate(2L)).thenReturn(Optional.of(owner));
        when(requests.findById(requestId.toString())).thenReturn(Optional.of(request));
        when(tasks.lockById(taskId.toString())).thenReturn(Optional.of(task));
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 10, 0);
        when(jdbc.queryForObject(eq("select current_timestamp"), org.mockito.ArgumentMatchers.<RowMapper<LocalDateTime>>any())).thenReturn(now);

        service.retry(1L, requestId, taskId, "12345678", "127.0.0.1", "browser");

        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
        assertThat(task.getAttempts()).isZero();
        assertThat(task.getNextAttemptAt()).isEqualTo(now);
        assertThat(task.getLastErrorCode()).isNull();
        assertThat(task.getEncryptedPayload()).isEqualTo("ciphertext");
        verify(appUsers).ensureSecondaryPinMatches(admin, "12345678");
        verify(tasks).saveAndFlush(task);
        verify(audit).record("admin", "127.0.0.1", "browser", LoginAuditStatus.ADMIN_ACTION,
                "WITHDRAWAL_TASK_RETRY", admin);
    }

    @Test void aTaskFromAnotherRequestCannotBeRequeued() {
        AppUser admin = user(1L, true, AppUserRole.ADMIN), owner = user(2L, false, AppUserRole.USER);
        var task = task("KAKAO_UNLINK_UNCONFIRMED"); task.setRequestId(UUID.randomUUID().toString());
        when(users.lockForAccountSecurityUpdate(1L)).thenReturn(Optional.of(admin));
        when(users.lockForAccountSecurityUpdate(2L)).thenReturn(Optional.of(owner));
        when(requests.findById(requestId.toString())).thenReturn(Optional.of(request()));
        when(tasks.lockById(taskId.toString())).thenReturn(Optional.of(task));
        assertThatThrownBy(() -> service.retry(1L, requestId, taskId, "12345678", null, null))
                .isInstanceOf(BadRequestException.class);
        assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.FAILED);
        verify(tasks, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test void aWrongPinNeverReadsOrChangesTheTask() {
        AppUser admin = user(1L, true, AppUserRole.ADMIN);
        when(users.lockForAccountSecurityUpdate(1L)).thenReturn(Optional.of(admin));
        doThrow(new BadCredentialsException("wrong")).when(appUsers).ensureSecondaryPinMatches(admin, "00000000");
        assertThatThrownBy(() -> service.retry(1L, requestId, taskId, "00000000", null, null))
                .isInstanceOf(BadCredentialsException.class);
        verify(requests, never()).findById(any());
        verify(tasks, never()).lockById(any());
    }

    private AppUser user(long id, boolean active, AppUserRole role) {
        AppUser user = new AppUser(); user.setId(id); user.setActive(active); user.setRole(role); user.setLoginId("admin");
        return user;
    }

    private AccountWithdrawalRequest request() {
        var request = new AccountWithdrawalRequest(); request.setId(requestId.toString()); request.setUserId(2L);
        request.setState(AccountWithdrawalRequest.State.IN_PROGRESS); return request;
    }

    private WithdrawalCleanupTask task(String code) {
        var task = new WithdrawalCleanupTask(); task.setId(taskId.toString()); task.setRequestId(requestId.toString());
        task.setKind(WithdrawalCleanupKind.KAKAO_UNLINK); task.setState(WithdrawalCleanupTask.State.FAILED);
        task.setEncryptedPayload("ciphertext"); task.setLastErrorCode(code); task.setAttempts(8); return task;
    }
}
