package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.social.KakaoIdentity;
import com.playdata.calen.account.social.KakaoOAuthProperties;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A successful OAuth exchange can reconnect a provider account while withdrawal is running. */
@Service
@RequiredArgsConstructor
public class KakaoWithdrawalReconnectGuard {
    private final SocialAccountRepository socialAccounts;
    private final AppUserRepository users;
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskStore tasks;
    private final KakaoOAuthProperties properties;
    private final EntityManager entities;

    /** Returns true when the caller must not log in or register the withdrawing account. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean requeueIfWithdrawing(KakaoIdentity identity) {
        if (identity == null || identity.providerUserId() == null) return false;
        var binding = socialAccounts.findWithUserByProviderAndProviderUserId(
                SocialLoginProvider.KAKAO, identity.providerUserId()).orElse(null);
        if (binding == null || binding.getUser() == null || binding.getUser().getId() == null) return false;
        Long userId = binding.getUser().getId();
        var user = users.lockForAccountSecurityUpdate(userId).orElse(null);
        if (user == null) return false;
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        entities.refresh(binding, LockModeType.PESSIMISTIC_WRITE);
        if (binding.getUser() == null || !userId.equals(binding.getUser().getId())
                || binding.getProvider() != SocialLoginProvider.KAKAO
                || !Objects.equals(identity.providerUserId(), binding.getProviderUserId())) {
            throw new ServiceUnavailableException("카카오 연결 상태를 확인할 수 없습니다.");
        }
        var request = requests.findByUserId(userId).orElse(null);
        if (request == null) return false; // Ordinary accounts and administrative deactivation remain unchanged.
        entities.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        if (!userId.equals(request.getUserId()) || request.getState() != AccountWithdrawalRequest.State.IN_PROGRESS) {
            throw new ServiceUnavailableException("카카오 연결 상태를 확인할 수 없습니다.");
        }
        Long expectedApp = properties.getExpectedAppId();
        boolean appVerified = !user.isActive() && identity.emailVerified() && expectedApp != null && expectedApp > 0
                && expectedApp.equals(identity.providerAppId()) && expectedApp.equals(binding.getProviderAppId())
                && expectedApp.equals(properties.getAdminAppId());
        // An active user with an in-progress withdrawal is also inconsistent. In
        // either case invalidate earlier success, but never call a provider on a guess.
        tasks.rearmKakaoAfterReconnect(UUID.fromString(request.getId()), binding.getId(), identity.providerUserId(), appVerified);
        return true;
    }
}
