package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.dto.AppUserResponse;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.SecondaryPinMismatchException;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
    private final SecondaryPinAttemptService secondaryPinAttemptService;

    public AppUser getRequiredUser(Long userId) {
        return appUserRepository.findById(userId)
                .filter(AppUser::isActive)
                .orElseThrow(() -> new NotFoundException("사용자 계정을 찾을 수 없습니다."));
    }

    public Optional<AppUser> findActiveUserByLoginId(String loginId) {
        return appUserRepository.findByLoginId(loginId)
                .filter(AppUser::isActive);
    }

    public Optional<AppUser> findUserByEmail(String emailRaw) {
        return appUserRepository.findByEmailIgnoreCase(normalizeEmailFormat(emailRaw));
    }

    public List<AppUser> searchActiveUsersForSharing(Long currentUserId, String query, int limit) {
        String normalizedQuery = query != null ? query.trim() : "";
        if (!StringUtils.hasText(normalizedQuery)) {
            return List.of();
        }

        int normalizedLimit = Math.max(1, Math.min(limit, 20));
        return appUserRepository.searchActiveUsersForInvitation(
                currentUserId,
                normalizedQuery,
                PageRequest.of(0, normalizedLimit)
        );
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
        return createUser(
                loginIdRaw,
                displayNameRaw,
                null,
                passwordRaw,
                secondaryPinRaw,
                role,
                true,
                true
        );
    }

    @Transactional
    public AppUser registerPendingEmailUser(
            String loginIdRaw,
            String displayNameRaw,
            String emailRaw,
            String passwordRaw,
            String secondaryPinRaw
    ) {
        return createUser(
                loginIdRaw,
                displayNameRaw,
                emailRaw,
                passwordRaw,
                secondaryPinRaw,
                AppUserRole.USER,
                false,
                false
        );
    }

    @Transactional
    public AppUser registerVerifiedEmailUser(
            String loginIdRaw,
            String displayNameRaw,
            String emailRaw,
            String passwordRaw,
            String secondaryPinRaw
    ) {
        return createUser(
                loginIdRaw,
                displayNameRaw,
                emailRaw,
                passwordRaw,
                secondaryPinRaw,
                AppUserRole.USER,
                true,
                true
        );
    }

    public void ensureSecondaryPinMatches(AppUser user, String secondaryPinRaw) {
        Long userId = user != null ? user.getId() : null;
        secondaryPinAttemptService.ensureAllowed(userId);

        String secondaryPin;
        try {
            secondaryPin = normalizeSecondaryPin(secondaryPinRaw);
        } catch (BadRequestException exception) {
            secondaryPinAttemptService.recordFailure(userId);
            throw exception;
        }
        if (!StringUtils.hasText(user.getSecondaryPinHash())
                || !passwordEncoder.matches(secondaryPin, user.getSecondaryPinHash())) {
            secondaryPinAttemptService.recordFailure(userId);
            throw new SecondaryPinMismatchException();
        }
        secondaryPinAttemptService.recordSuccess(userId);
    }

    public void verifySecondaryPin(Long userId, String secondaryPinRaw) {
        ensureSecondaryPinMatches(getRequiredUser(userId), secondaryPinRaw);
    }

    public String verifyPrivacyAccess(Long userId, String passwordRaw, String secondaryPinRaw) {
        AppUser user = getRequiredUser(userId);
        String password = passwordRaw != null ? passwordRaw.trim() : "";
        if (!StringUtils.hasText(user.getPasswordHash()) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BadRequestException("현재 비밀번호가 일치하지 않습니다.");
        }

        String secondaryPin = normalizeSecondaryPin(secondaryPinRaw);
        ensureSecondaryPinMatches(user, secondaryPin);
        return secondaryPin;
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

    private AppUser createUser(
            String loginIdRaw,
            String displayNameRaw,
            String emailRaw,
            String passwordRaw,
            String secondaryPinRaw,
            AppUserRole role,
            boolean active,
            boolean emailVerified
    ) {
        String loginId = normalizeRequired(loginIdRaw, "로그인 ID는 필수입니다.", 60);
        String displayName = normalizeRequired(displayNameRaw, "표시 이름은 필수입니다.", 80);
        String password = passwordRaw != null ? passwordRaw.trim() : "";
        String secondaryPin = normalizeSecondaryPin(secondaryPinRaw);
        String email = emailRaw == null ? null : normalizeRegistrationEmail(emailRaw);
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
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setSecondaryPinHash(passwordEncoder.encode(secondaryPin));
        user.setRole(normalizedRole);
        user.setActive(active);
        user.setEmailVerified(emailVerified);

        AppUser savedUser = appUserRepository.save(user);
        if (active && emailVerified) {
            accountSetupService.initializeDefaults(savedUser);
        }
        return savedUser;
    }

    private String normalizeRegistrationEmail(String emailRaw) {
        String email = normalizeEmailFormat(emailRaw);
        if (appUserRepository.existsByEmailIgnoreCase(email)) {
            throw new BadRequestException("이미 사용 중인 이메일입니다.");
        }
        return email;
    }

    private String normalizeEmailFormat(String emailRaw) {
        String email = emailRaw != null ? emailRaw.trim().toLowerCase(Locale.ROOT) : "";
        if (email.length() > 254 || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BadRequestException("올바른 이메일 주소를 입력해 주세요.");
        }
        return email;
    }

    private String normalizeRequired(String valueRaw, String message, int maxLength) {
        String value = valueRaw != null ? valueRaw.trim() : "";
        if (!StringUtils.hasText(value) || value.length() > maxLength) {
            throw new BadRequestException(message);
        }
        return value;
    }
}
