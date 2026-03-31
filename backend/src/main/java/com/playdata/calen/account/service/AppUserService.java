package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.dto.AppUserResponse;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.SecondaryPinMismatchException;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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

    public Optional<AppUser> findActiveUserByLoginId(String loginId) {
        return appUserRepository.findByLoginId(loginId)
                .filter(AppUser::isActive);
    }

    @Transactional
    public AppUser registerUser(String loginIdRaw, String displayNameRaw, String passwordRaw, String secondaryPinRaw) {
        return registerUser(loginIdRaw, displayNameRaw, passwordRaw, secondaryPinRaw, AppUserRole.USER);
    }

    @Transactional
    public AppUser registerUser(
            String loginIdRaw,
            String displayNameRaw,
            String passwordRaw,
            String secondaryPinRaw,
            AppUserRole role
    ) {
        String loginId = loginIdRaw.trim();
        String displayName = displayNameRaw.trim();
        String password = passwordRaw.trim();
        String secondaryPin = normalizeSecondaryPin(secondaryPinRaw);
        AppUserRole normalizedRole = role != null ? role : AppUserRole.USER;

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
        user.setSecondaryPinHash(passwordEncoder.encode(secondaryPin));
        user.setRole(normalizedRole);
        user.setActive(true);

        AppUser savedUser = appUserRepository.save(user);
        accountSetupService.initializeDefaults(savedUser);
        return savedUser;
    }

    public void ensureSecondaryPinMatches(AppUser user, String secondaryPinRaw) {
        String secondaryPin = normalizeSecondaryPin(secondaryPinRaw);
        if (!StringUtils.hasText(user.getSecondaryPinHash())
                || !passwordEncoder.matches(secondaryPin, user.getSecondaryPinHash())) {
            throw new SecondaryPinMismatchException();
        }
    }

    public void verifySecondaryPin(Long userId, String secondaryPinRaw) {
        ensureSecondaryPinMatches(getRequiredUser(userId), secondaryPinRaw);
    }

    @Transactional
    public void updatePassword(Long userId, String secondaryPinRaw, String newPasswordRaw) {
        AppUser user = getRequiredUser(userId);
        ensureSecondaryPinMatches(user, secondaryPinRaw);

        String newPassword = newPasswordRaw != null ? newPasswordRaw.trim() : "";
        if (newPassword.length() < 8) {
            throw new BadRequestException("비밀번호는 8자 이상이어야 합니다.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
    }

    @Transactional
    public String updateSecondaryPin(Long userId, String currentSecondaryPinRaw, String newSecondaryPinRaw) {
        AppUser user = getRequiredUser(userId);
        ensureSecondaryPinMatches(user, currentSecondaryPinRaw);

        String normalizedSecondaryPin = normalizeSecondaryPin(newSecondaryPinRaw);
        user.setSecondaryPinHash(passwordEncoder.encode(normalizedSecondaryPin));
        return normalizedSecondaryPin;
    }

    public AppUserRole normalizeRole(AppUser user) {
        return user.getRole() != null ? user.getRole() : AppUserRole.USER;
    }

    public AppUserResponse toResponse(AppUser user) {
        AppUserRole role = normalizeRole(user);
        return new AppUserResponse(
                user.getId(),
                user.getLoginId(),
                user.getDisplayName(),
                role,
                role.isAdmin(),
                user.isActive()
        );
    }

    private String normalizeSecondaryPin(String secondaryPinRaw) {
        String secondaryPin = secondaryPinRaw != null ? secondaryPinRaw.trim() : "";
        if (!secondaryPin.matches("\\d{8}")) {
            throw new BadRequestException("2차 비밀번호는 숫자 8자리여야 합니다.");
        }
        return secondaryPin;
    }
}
