package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.SocialAccount;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.config.SocialCredentialSchemaSupport;
import com.playdata.calen.account.security.AccountSecurityVersion;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.social.KakaoIdentity;
import com.playdata.calen.account.social.KakaoLinkStateChangedException;
import com.playdata.calen.account.social.KakaoOAuthProperties;
import com.playdata.calen.common.exception.BadRequestException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SocialAccountService {

    private final SocialAccountRepository socialAccountRepository;
    private final AppUserService appUserService;
    private final AppUserRepository appUserRepository;
    private final SocialCredentialSchemaSupport socialCredentialSchemaSupport;
    private final KakaoOAuthProperties kakaoProperties;
    private final EntityManager entities;

    public boolean isKakaoLinked(Long userId) {
        return socialAccountRepository.existsByUserIdAndProvider(userId, SocialLoginProvider.KAKAO);
    }

    @Transactional
    public void linkKakaoToExistingUser(Long userId, KakaoIdentity identity, String securityVersion) {
        AppUser user = appUserRepository.findActiveForPreferenceUpdate(userId)
                .orElseThrow(() -> new BadRequestException("사용자 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (!AccountSecurityVersion.matchesKakaoLink(user, securityVersion)) throw new KakaoLinkStateChangedException();
        if (isKakaoLinked(userId)) {
            throw new BadRequestException("이미 카카오 계정이 연결되어 있습니다.");
        }
        validateNewKakaoIdentity(identity);
        linkNewAccount(user, identity);
    }

    public Optional<AppUser> findActiveUser(SocialLoginProvider provider, String providerUserId) {
        if (provider == null || !StringUtils.hasText(providerUserId)) {
            return Optional.empty();
        }
        return socialAccountRepository.findWithUserByProviderAndProviderUserId(provider, providerUserId.trim())
                .map(SocialAccount::getUser)
                .filter(AppUser::isActive);
    }

    @Transactional
    public AppUser registerKakaoUser(
            KakaoIdentity identity,
            String loginId,
            String displayName,
            String password,
            String secondaryPin
    ) {
        validateNewKakaoIdentity(identity);
        AppUser user = appUserService.registerVerifiedEmailUser(
                loginId,
                displayName,
                identity.email(),
                password,
                secondaryPin
        );
        return linkNewAccount(user, identity);
    }

    @Transactional
    public AppUser registerKakaoOnlyUser(KakaoIdentity identity, String displayName, String secondaryPin) {
        validateNewKakaoIdentity(identity);
        socialCredentialSchemaSupport.requireNullablePasswordColumn();
        return linkNewAccount(appUserService.registerKakaoOnlyUser(displayName, identity.email(), secondaryPin), identity);
    }

    private void validateNewKakaoIdentity(KakaoIdentity identity) {
        if (identity == null || !identity.emailVerified() || !StringUtils.hasText(identity.email())
                || !StringUtils.hasText(identity.providerUserId())) {
            throw new BadRequestException("카카오 계정의 인증된 이메일이 필요합니다.");
        }
        if ((identity.providerAppId() != null && identity.providerAppId() <= 0)
                || (kakaoProperties.getExpectedAppId() != null && kakaoProperties.getExpectedAppId() <= 0)
                || ((kakaoProperties.getExpectedAppId() != null || identity.providerAppId() != null)
                && !java.util.Objects.equals(kakaoProperties.getExpectedAppId(), identity.providerAppId()))) {
            throw new BadRequestException("카카오 앱 인증이 만료되었습니다. 다시 인증해 주세요.");
        }
        if (socialAccountRepository.existsByProviderAndProviderUserId(SocialLoginProvider.KAKAO, identity.providerUserId())) {
            throw new BadRequestException("이미 연결된 카카오 계정입니다.");
        }
    }

    private AppUser linkNewAccount(AppUser user, KakaoIdentity identity) {
        SocialAccount account = new SocialAccount();
        account.setUser(user);
        account.setProvider(SocialLoginProvider.KAKAO);
        account.setProviderUserId(identity.providerUserId());
        account.setProviderAppId(identity.providerAppId());
        account.setEmail(identity.email());
        socialAccountRepository.save(account);
        return user;
    }
}
