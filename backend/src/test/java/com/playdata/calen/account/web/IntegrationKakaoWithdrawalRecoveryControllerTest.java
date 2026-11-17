package com.playdata.calen.account.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.service.KakaoWithdrawalRecoveryService;
import com.playdata.calen.account.withdrawal.RecoveryRequestThrottle;
import com.playdata.calen.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class IntegrationKakaoWithdrawalRecoveryControllerTest {
    private final KakaoWithdrawalRecoveryService recovery = mock(KakaoWithdrawalRecoveryService.class);
    private final RecoveryRequestThrottle throttle = mock(RecoveryRequestThrottle.class);
    private final IntegrationKakaoWithdrawalRecoveryController controller =
            new IntegrationKakaoWithdrawalRecoveryController(recovery, throttle);

    @Test void aThrottledEmailGetsTheSameGenericResponseWithoutSendingMail() {
        var request = new MockHttpServletRequest();
        when(throttle.allowIp(request)).thenReturn(true);
        when(throttle.allowEmail("person@example.test")).thenReturn(false);
        var response = controller.request(new IntegrationKakaoWithdrawalRecoveryController.EmailRequest("person@example.test"), null, request);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("accepted", true);
        verify(recovery, never()).requestEmailProof(any());
    }

    @Test void ipLimitAppliesBeforeAnyEmailLookup() {
        var request = new MockHttpServletRequest();
        when(throttle.allowIp(request)).thenReturn(false);
        assertThatThrownBy(() -> controller.request(new IntegrationKakaoWithdrawalRecoveryController.EmailRequest("person@example.test"), null, request))
                .isInstanceOf(TooManyRequestsException.class);
        verify(throttle, never()).allowEmail(any());
        verify(recovery, never()).requestEmailProof(any());
    }
}
