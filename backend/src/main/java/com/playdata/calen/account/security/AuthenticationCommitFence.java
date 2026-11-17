package com.playdata.calen.account.security;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.social.KakaoIdentity;
import com.playdata.calen.account.social.KakaoOAuthProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Revalidates authentication under the account lock before creating a session or token. */
@Service
@RequiredArgsConstructor
public class AuthenticationCommitFence {
    private final AppUserRepository users;
    private final SocialAccountRepository socialAccounts;
    private final KakaoOAuthProperties kakaoProperties;
    private final EntityManager entities;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public <T> T withLocal(AppUserPrincipal authenticated, String verifiedPinHash, Function<AppUser, T> completion) {
        AppUser current = lockActive(authenticated.userId());
        if (!Objects.equals(current.getLoginId(), authenticated.loginId())
                || current.getPasswordHash() == null
                || !Objects.equals(current.getPasswordHash(), authenticated.passwordHash())
                || !Objects.equals(current.getSecondaryPinHash(), verifiedPinHash)) {
            throw rejected();
        }
        return completion.apply(current);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public <T> T withKakao(Long userId, KakaoIdentity identity, Function<AppUser, T> completion) {
        AppUser current = lockActive(userId);
        if (identity == null || identity.providerUserId() == null) throw rejected();
        var account = socialAccounts.findWithUserByProviderAndProviderUserId(
                SocialLoginProvider.KAKAO, identity.providerUserId()).orElseThrow(AuthenticationCommitFence::rejected);
        entities.refresh(account, LockModeType.PESSIMISTIC_WRITE);
        if (!current.getId().equals(account.getUser().getId())) throw rejected();
        Long verifiedApp = identity.providerAppId();
        Long expectedApp = kakaoProperties.getExpectedAppId();
        if ((expectedApp != null && expectedApp <= 0) || (verifiedApp != null && verifiedApp <= 0)
                || ((expectedApp != null || verifiedApp != null) && !Objects.equals(expectedApp, verifiedApp))
                || (account.getProviderAppId() != null && !account.getProviderAppId().equals(verifiedApp))) throw rejected();
        if (account.getProviderAppId() == null && verifiedApp != null) {
            // The operator must first verify that legacy accounts belong to the configured app.
            account.setProviderAppId(verifiedApp);
        }
        return completion.apply(current);
    }

    private AppUser lockActive(Long userId) {
        AppUser user = users.lockForAccountSecurityUpdate(userId).orElseThrow(AuthenticationCommitFence::rejected);
        // A request-scoped EntityManager may already hold a stale managed entity even
        // after SELECT FOR UPDATE. Explicitly refresh before examining its fields.
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (!user.isActive() || !user.isEmailVerified()) throw rejected();
        return user;
    }

    private static BadCredentialsException rejected() {
        return new BadCredentialsException("로그인 정보가 변경되었습니다. 다시 로그인해 주세요.");
    }
}
