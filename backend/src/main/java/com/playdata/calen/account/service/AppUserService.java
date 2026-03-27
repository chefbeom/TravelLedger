package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.dto.AppUserResponse;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AppUserService {

    private final AppUserRepository appUserRepository;
    private final AccountSetupService accountSetupService;
    private final PasswordEncoder passwordEncoder;

    public AppUser getRequiredUser(Long userId) {
        return appUserRepository.findById(userId)
                .filter(AppUser::isActive)
                .orElseThrow(() -> new NotFoundException("사용자 계정을 찾을 수 없습니다."));
    }

    @Transactional
    public AppUser registerUser(String loginIdRaw, String displayNameRaw, String passwordRaw) {
        String loginId = loginIdRaw.trim();
        String displayName = displayNameRaw.trim();
        String password = passwordRaw.trim();

        if (appUserRepository.existsByLoginId(loginId)) {
            throw new BadRequestException("사용할 수 없는 로그인 ID입니다.");
        }
        if (password.length() < 8) {
            throw new BadRequestException("비밀번호는 8자 이상이어야 합니다.");
        }

        AppUser user = new AppUser();
        user.setLoginId(loginId);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);

        AppUser savedUser = appUserRepository.save(user);
        accountSetupService.initializeDefaults(savedUser);
        return savedUser;
    }

    public AppUserResponse toResponse(AppUser user) {
        return new AppUserResponse(
                user.getId(),
                user.getLoginId(),
                user.getDisplayName(),
                user.isActive()
        );
    }
}
