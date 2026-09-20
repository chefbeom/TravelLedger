package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.SocialAccount;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.social.KakaoIdentity;
import com.playdata.calen.common.exception.BadRequestException;
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
        if (identity == null || !identity.emailVerified() || !StringUtils.hasText(identity.email())) {
            throw new BadRequestException("카카오 계정의 인증된 이메일이 필요합니다.");
        }
        if (socialAccountRepository.existsByProviderAndProviderUserId(
                SocialLoginProvider.KAKAO,
                identity.providerUserId()
        )) {
            throw new BadRequestException("이미 연결된 카카오 계정입니다.");
        }

        AppUser user = appUserService.registerVerifiedEmailUser(
                loginId,
                displayName,
                identity.email(),
                password,
                secondaryPin
        );
        SocialAccount account = new SocialAccount();
        account.setUser(user);
        account.setProvider(SocialLoginProvider.KAKAO);
        account.setProviderUserId(identity.providerUserId());
        account.setEmail(identity.email());
        socialAccountRepository.save(account);
        return user;
    }
}
