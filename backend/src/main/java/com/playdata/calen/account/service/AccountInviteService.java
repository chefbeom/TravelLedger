package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AccountInvite;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.dto.AccountInviteCreateResponse;
import com.playdata.calen.account.dto.AccountInviteDetailsResponse;
import com.playdata.calen.account.repository.AccountInviteRepository;
import com.playdata.calen.common.exception.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountInviteService {

    private static final int DEFAULT_EXPIRES_IN_HOURS = 72;
    private static final int MAX_EXPIRES_IN_HOURS = 168;
    private static final String INVALID_INVITE_MESSAGE = "초대 링크가 유효하지 않습니다.";

    private final AccountInviteRepository accountInviteRepository;
    private final AppUserService appUserService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public AccountInviteCreateResponse createInvite(Long creatorUserId, Integer expiresInHours) {
        AppUser creator = appUserService.getRequiredUser(creatorUserId);
        int normalizedHours = normalizeExpiresInHours(expiresInHours);

        String rawToken = generateUniqueToken();

        AccountInvite invite = new AccountInvite();
        invite.setCreatedBy(creator);
        invite.setTokenHash(hashToken(rawToken));
        invite.setExpiresAt(LocalDateTime.now().plusHours(normalizedHours));

        AccountInvite savedInvite = accountInviteRepository.save(invite);
        return new AccountInviteCreateResponse(rawToken, savedInvite.getExpiresAt());
    }

    public AccountInviteDetailsResponse getInviteDetails(String rawToken) {
        AccountInvite invite = accountInviteRepository.findWithCreatorByTokenHash(hashToken(rawToken))
                .orElseThrow(() -> new BadRequestException(INVALID_INVITE_MESSAGE));

        ensureAvailable(invite);
        return new AccountInviteDetailsResponse(
                invite.getCreatedBy().getDisplayName(),
                invite.getExpiresAt()
        );
    }

    @Transactional
    public AppUser acceptInvite(String rawToken, String loginId, String displayName, String password, String secondaryPin) {
        AccountInvite invite = accountInviteRepository.findForUpdateByTokenHash(hashToken(rawToken))
                .orElseThrow(() -> new BadRequestException(INVALID_INVITE_MESSAGE));

        ensureAvailable(invite);

        AppUser createdUser = appUserService.registerUser(loginId, displayName, password, secondaryPin);
        invite.setUsedBy(createdUser);
        invite.setUsedAt(LocalDateTime.now());
        return createdUser;
    }

    private void ensureAvailable(AccountInvite invite) {
        LocalDateTime now = LocalDateTime.now();
        if (invite.getUsedAt() != null || invite.getExpiresAt().isBefore(now)) {
            throw new BadRequestException(INVALID_INVITE_MESSAGE);
        }
    }

    private int normalizeExpiresInHours(Integer expiresInHours) {
        if (expiresInHours == null) {
            return DEFAULT_EXPIRES_IN_HOURS;
        }
        return Math.min(Math.max(expiresInHours, 1), MAX_EXPIRES_IN_HOURS);
    }

    private String generateUniqueToken() {
        String rawToken;
        String hashedToken;
        do {
            rawToken = generateToken();
            hashedToken = hashToken(rawToken);
        } while (accountInviteRepository.findByTokenHash(hashedToken).isPresent());
        return rawToken;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            throw new BadRequestException(INVALID_INVITE_MESSAGE);
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Invite token hashing is unavailable.", exception);
        }
    }
}
