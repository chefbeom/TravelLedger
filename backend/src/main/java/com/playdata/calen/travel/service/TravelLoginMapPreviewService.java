package com.playdata.calen.travel.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.dto.AdminLoginMapPreviewResponse;
import com.playdata.calen.travel.dto.AdminLoginMapPreviewUpdateRequest;
import com.playdata.calen.travel.dto.TravelLoginMapPreviewResponse;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Provides a deliberately reduced, read-only map preview for the unauthenticated login page. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TravelLoginMapPreviewService {

    private static final int SETTINGS_ID = 1;
    private static final String PREVIEW_TITLE = "여행 기록 미리보기";
    private static final Pattern SHARE_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{16,80}");
    private static final Pattern SHARE_URL_TOKEN_PATTERN = Pattern.compile("(?:^|[#/])travel-share/([A-Za-z0-9_-]{16,80})(?:$|[/?#])");

    private final JdbcTemplate jdbcTemplate;
    private final TravelService travelService;

    private volatile boolean enabled;
    private volatile String shareToken;

    @PostConstruct
    void initialize() {
        try {
            jdbcTemplate.execute("create table if not exists travel_login_map_preview_settings ("
                    + "setting_id integer primary key, enabled boolean not null, share_token varchar(80), "
                    + "updated_at timestamp default current_timestamp)");
            List<StoredSettings> rows = jdbcTemplate.query(
                    "select enabled, share_token from travel_login_map_preview_settings where setting_id = ?",
                    (resultSet, rowNum) -> new StoredSettings(resultSet.getBoolean("enabled"), resultSet.getString("share_token")),
                    SETTINGS_ID
            );
            if (rows.isEmpty()) {
                jdbcTemplate.update(
                        "insert into travel_login_map_preview_settings (setting_id, enabled, share_token, updated_at) values (?, ?, ?, current_timestamp)",
                        SETTINGS_ID,
                        false,
                        null
                );
                enabled = false;
                shareToken = null;
            } else {
                enabled = rows.get(0).enabled();
                shareToken = rows.get(0).shareToken();
            }
        } catch (RuntimeException exception) {
            // Keep the public login route fail-closed if this optional setting cannot be read.
            enabled = false;
            shareToken = null;
            log.warn("Login travel map preview settings could not be loaded; preview remains disabled.");
        }
    }

    public AdminLoginMapPreviewResponse getAdminSettings() {
        return new AdminLoginMapPreviewResponse(enabled, shareToken != null && !shareToken.isBlank());
    }

    @Transactional
    public AdminLoginMapPreviewResponse updateAdminSettings(AdminLoginMapPreviewUpdateRequest request) {
        String nextToken = request.clearShareLink() ? null : shareToken;
        String suppliedLink = request.shareLinkOrToken() == null ? "" : request.shareLinkOrToken().trim();
        if (!suppliedLink.isEmpty()) {
            nextToken = extractShareToken(suppliedLink);
            try {
                TravelLoginMapPreviewResponse preview = travelService.getTravelMapShareLoginPreview(nextToken);
                if (!preview.enabled()) {
                    throw new BadRequestException("공유 링크에 로그인 미리보기로 표시할 여행 기록이 없습니다.");
                }
            } catch (RuntimeException exception) {
                if (exception instanceof BadRequestException badRequestException) {
                    throw badRequestException;
                }
                throw new BadRequestException("활성 상태의 여행 지도 공유 링크를 확인할 수 없습니다.");
            }
        }
        if (request.enabled() && (nextToken == null || nextToken.isBlank())) {
            throw new BadRequestException("미리보기를 켜려면 먼저 여행 지도 공유 링크를 등록해 주세요.");
        }

        int updated = jdbcTemplate.update(
                "update travel_login_map_preview_settings set enabled = ?, share_token = ?, updated_at = current_timestamp where setting_id = ?",
                request.enabled(),
                nextToken,
                SETTINGS_ID
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    "insert into travel_login_map_preview_settings (setting_id, enabled, share_token, updated_at) values (?, ?, ?, current_timestamp)",
                    SETTINGS_ID,
                    request.enabled(),
                    nextToken
            );
        }

        shareToken = nextToken;
        enabled = request.enabled();
        return getAdminSettings();
    }

    public TravelLoginMapPreviewResponse getPublicPreview() {
        String configuredToken = shareToken;
        if (!enabled || configuredToken == null || configuredToken.isBlank()) {
            return emptyPreview();
        }

        try {
            return travelService.getTravelMapShareLoginPreview(configuredToken);
        } catch (RuntimeException exception) {
            // A revoked or stale share must never break sign-in or reveal details in the public response.
            log.warn("Configured login travel map preview is unavailable; hiding preview data.");
            return emptyPreview();
        }
    }

    private String extractShareToken(String value) {
        if (SHARE_TOKEN_PATTERN.matcher(value).matches()) {
            return value;
        }
        Matcher matcher = SHARE_URL_TOKEN_PATTERN.matcher(value);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new BadRequestException("여행 지도 공유 링크 또는 공유 토큰 형식이 올바르지 않습니다.");
    }

    private TravelLoginMapPreviewResponse emptyPreview() {
        return new TravelLoginMapPreviewResponse(false, PREVIEW_TITLE, 0, 0, List.of(), List.of());
    }

    private record StoredSettings(boolean enabled, String shareToken) {
    }
}
