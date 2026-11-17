package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.EmailVerificationTokenRepository;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EmailVerificationBaseUrlTest {
    private final EmailVerificationService verification = new EmailVerificationService(
            mock(AppUserService.class), mock(AppUserRepository.class), mock(AccountSetupService.class),
            mock(EmailVerificationTokenRepository.class), mock(EmailSignupExpiryService.class),
            mock(EmailSignupDatabaseClock.class), mock(EmailVerificationSender.class), mock(EntityManager.class));

    @Test void permitsHttpsAndOnlyLoopbackHttpForEveryVerificationFlow() {
        for (String base : List.of("https://app.example.test", "https://app.example.test/app/",
                "http://localhost:5173", "http://127.0.0.1:5173", "http://[::1]:5173")) {
            ReflectionTestUtils.setField(verification, "baseUrl", base);
            String normalized = base.replaceAll("/+$", "");
            assertThat(verification.buildVerificationUrl("safe-token")).isEqualTo(normalized + "/#verify-email/safe-token");
            assertThat(verification.buildProfileVerificationUrl("safe-token")).isEqualTo(normalized + "/#verify-profile-email/safe-token");
            assertThat(verification.buildWithdrawalRecoveryUrl("safe-token")).isEqualTo(normalized + "/#withdraw-kakao/safe-token");
        }
    }

    @Test void rejectsInsecureRemoteAndAmbiguousUrlsWithoutExposingTheToken() {
        for (String base : List.of("", "http://app.example.test", "http://192.168.35.123:8081",
                "http://localhost.attacker.test", "http://localhost@attacker.test", "https://user:password@app.example.test",
                "https://app.example.test/?next=other", "https://app.example.test/#fragment", "javascript:alert(1)")) {
            ReflectionTestUtils.setField(verification, "baseUrl", base);
            assertThatThrownBy(() -> verification.buildVerificationUrl("sensitive-token"))
                    .isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining("sensitive-token");
        }
    }
}
