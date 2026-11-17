package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.account.social.KakaoOAuthProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class KakaoWithdrawalCleanupExecutorTest {
    private final WithdrawalCleanupTaskStore store = mock(WithdrawalCleanupTaskStore.class);
    private final KakaoOAuthClient kakao = mock(KakaoOAuthClient.class);
    private final KakaoOAuthProperties properties = new KakaoOAuthProperties();
    private final KakaoWithdrawalPreflight preflight = mock(KakaoWithdrawalPreflight.class);
    private final KakaoWithdrawalCleanupExecutor executor = new KakaoWithdrawalCleanupExecutor(store, kakao, properties, preflight);
    private final UUID id = UUID.randomUUID();
    private final UUID token = UUID.randomUUID();

    @Test void confirmsSuccessOnlyAfterTheProviderAndTaskStoreBothSucceed() {
        configured();
        when(store.succeed(id, token)).thenReturn(true);
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.SUCCEEDED);
        verify(kakao).unlinkAccount("123456789");
        verify(store).succeed(id, token);
    }

    @Test void aLostResponseIsNotMistakenForACompletedUnlink() {
        configured();
        doThrow(new ServiceUnavailableException("private provider error")).when(kakao).unlinkAccount(any());
        when(store.fail(eq(id), eq(token), eq(true), any(), eq("KAKAO_UNLINK_UNCONFIRMED"))).thenReturn(true);
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.RETRY_PENDING);
        verify(store).fail(id, token, true, Duration.ofSeconds(30), "KAKAO_UNLINK_UNCONFIRMED");
        verify(store, never()).succeed(any(), any());
    }

    @Test void databaseFailureAfterConfirmedUnlinkIsNotMisreportedAsProviderFailure() {
        configured();
        when(store.succeed(id, token)).thenThrow(new IllegalStateException("test database unavailable"));
        assertThatThrownBy(executor::processNext).isInstanceOf(IllegalStateException.class);
        verify(store, never()).fail(any(), any(), anyBoolean(), any(), any());
    }

    @Test void confirmedProviderResponseCannotCompleteAnExpiredClaim() {
        configured();
        when(store.succeed(id, token)).thenReturn(false);
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.LOST_LEASE);
        verify(store, never()).fail(any(), any(), anyBoolean(), any(), any());
    }

    @Test void repeatedUnconfirmedResponsesRequireManualReconciliation() {
        configured(8);
        doThrow(new ServiceUnavailableException("unconfirmed")).when(kakao).unlinkAccount(any());
        when(store.fail(eq(id), eq(token), eq(false), any(), eq("KAKAO_UNLINK_UNCONFIRMED"))).thenReturn(true);
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.MANUAL_REVIEW);
        verify(store).fail(id, token, false, Duration.ofMinutes(30), "KAKAO_UNLINK_UNCONFIRMED");
    }

    @Test void invalidSubjectIsNotRetriedAndAnExpiredClaimIsNeverReportedAsSuccess() {
        configured();
        doThrow(new BadRequestException("invalid")).when(kakao).unlinkAccount(any());
        when(store.fail(eq(id), eq(token), eq(false), any(), eq("KAKAO_TARGET_INVALID"))).thenReturn(false);
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.LOST_LEASE);
        verify(store, never()).succeed(any(), any());
    }

    @Test void staleOrMismatchedWithdrawalNeverCallsTheProvider() {
        configured();
        when(preflight.verify(any(), any())).thenReturn(KakaoWithdrawalPreflight.Result.LOST_LEASE);
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.LOST_LEASE);
        verify(kakao, never()).unlinkAccount(any());
        verify(store, never()).succeed(any(), any());
    }

    @Test void invalidAccountStateRequiresManualReviewWithoutCallingTheProvider() {
        configured();
        when(preflight.verify(any(), any())).thenReturn(KakaoWithdrawalPreflight.Result.MANUAL_REVIEW);
        when(store.fail(eq(id), eq(token), eq(false), any(), eq("KAKAO_ACCOUNT_STATE_INVALID"))).thenReturn(true);
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.MANUAL_REVIEW);
        verify(kakao, never()).unlinkAccount(any());
        verify(store, never()).succeed(any(), any());
    }

    @Test void missingConfigurationOrAnExcessiveHttpTimeoutDoesNotConsumeTasks() {
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.CONFIGURATION_REQUIRED);
        verifyNoInteractions(store);
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        properties.setReadTimeout(Duration.ofMinutes(5));
        assertThat(executor.processNext()).isEqualTo(KakaoWithdrawalCleanupExecutor.Result.CONFIGURATION_REQUIRED);
        verifyNoInteractions(store);
    }

    @Test void noExternalCallIsAllowedInsideAnExistingTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(executor::processNext).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(kakao, store);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private void configured() { configured(1); }
    private void configured(int attempt) {
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        when(preflight.verify(any(), any())).thenReturn(KakaoWithdrawalPreflight.Result.READY);
        var claim = new WithdrawalCleanupTaskStore.Claim(UUID.randomUUID(), id, WithdrawalCleanupKind.KAKAO_UNLINK,
                token, LocalDateTime.now().plusMinutes(5), attempt, "123456789");
        when(store.claimNext(Set.of(WithdrawalCleanupKind.KAKAO_UNLINK), Duration.ofMinutes(5)))
                .thenReturn(Optional.of(claim));
    }
}
