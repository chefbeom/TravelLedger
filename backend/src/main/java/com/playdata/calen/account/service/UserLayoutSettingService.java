package com.playdata.calen.account.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.UserLayoutSetting;
import com.playdata.calen.account.dto.UserLayoutSettingRequest;
import com.playdata.calen.account.dto.UserLayoutSettingResponse;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.UserLayoutSettingRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserLayoutSettingService {

    private static final int MAX_PAYLOAD_JSON_LENGTH = 200_000;
    private static final Pattern SCOPE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,79}$");

    private final AppUserRepository appUserRepository;
    private final UserLayoutSettingRepository userLayoutSettingRepository;
    private final ObjectMapper objectMapper;

    public UserLayoutSettingResponse getLayoutSetting(Long userId, String scope) {
        String normalizedScope = normalizeScope(scope);
        getRequiredActiveUser(userId);
        return userLayoutSettingRepository.findByOwnerIdAndLayoutScope(userId, normalizedScope)
                .map(this::toResponse)
                .orElseGet(() -> new UserLayoutSettingResponse(normalizedScope, 1, null, null));
    }

    @Transactional
    public UserLayoutSettingResponse saveLayoutSetting(
            Long userId,
            String scope,
            UserLayoutSettingRequest request
    ) {
        String normalizedScope = normalizeScope(scope);
        if (request == null || request.payload() == null || request.payload().isNull()) {
            throw new BadRequestException("Layout payload is required.");
        }

        String payloadJson = writePayload(request.payload());
        if (payloadJson.length() > MAX_PAYLOAD_JSON_LENGTH) {
            throw new BadRequestException("Layout payload is too large.");
        }

        AppUser owner = getRequiredActiveUser(userId);
        UserLayoutSetting setting = userLayoutSettingRepository.findByOwnerIdAndLayoutScope(userId, normalizedScope)
                .orElseGet(() -> {
                    UserLayoutSetting next = new UserLayoutSetting();
                    next.setOwner(owner);
                    next.setLayoutScope(normalizedScope);
                    return next;
                });
        setting.setLayoutVersion(normalizeVersion(request.version()));
        setting.setPayloadJson(payloadJson);

        return toResponse(userLayoutSettingRepository.save(setting));
    }

    private AppUser getRequiredActiveUser(Long userId) {
        return appUserRepository.findById(userId)
                .filter(AppUser::isActive)
                .orElseThrow(() -> new NotFoundException("User account was not found."));
    }

    private String normalizeScope(String scope) {
        String normalized = scope == null ? "" : scope.trim().toLowerCase();
        if (!SCOPE_PATTERN.matcher(normalized).matches()) {
            throw new BadRequestException("Invalid layout scope.");
        }
        return normalized;
    }

    private Integer normalizeVersion(Integer version) {
        if (version == null || version < 1) {
            return 1;
        }
        return Math.min(version, 999);
    }

    private String writePayload(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("Layout payload is invalid.");
        }
    }

    private JsonNode readPayload(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private UserLayoutSettingResponse toResponse(UserLayoutSetting setting) {
        return new UserLayoutSettingResponse(
                setting.getLayoutScope(),
                setting.getLayoutVersion(),
                readPayload(setting.getPayloadJson()),
                setting.getUpdatedAt()
        );
    }
}
