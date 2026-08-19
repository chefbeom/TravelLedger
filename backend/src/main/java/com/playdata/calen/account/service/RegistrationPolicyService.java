package com.playdata.calen.account.service;

import com.playdata.calen.account.dto.PublicRegistrationOptionsResponse;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the public sign-up policy separate from invitation acceptance. Future
 * OAuth providers can expose their keys through the same options response.
 */
@Service
@RequiredArgsConstructor
public class RegistrationPolicyService {

    private static final String SETTINGS_TABLE = "registration_policy_settings";
    private static final String PUBLIC_REGISTRATION_ENABLED = "public-registration-enabled";

    private final JdbcTemplate jdbcTemplate;
    private volatile boolean publicRegistrationEnabled;

    @PostConstruct
    void initialize() {
        try {
            jdbcTemplate.execute("create table if not exists " + SETTINGS_TABLE
                    + " (setting_key varchar(128) primary key, setting_value varchar(4000), updated_at timestamp default current_timestamp)");
            publicRegistrationEnabled = readBoolean(PUBLIC_REGISTRATION_ENABLED, false);
        } catch (RuntimeException exception) {
            // Fail closed: invitations remain available, while anonymous sign-up does not open unexpectedly.
            publicRegistrationEnabled = false;
        }
    }

    public PublicRegistrationOptionsResponse getPublicOptions() {
        return new PublicRegistrationOptionsResponse(publicRegistrationEnabled, List.of());
    }

    @Transactional
    public PublicRegistrationOptionsResponse updatePublicRegistration(boolean enabled) {
        publicRegistrationEnabled = enabled;
        persist(PUBLIC_REGISTRATION_ENABLED, Boolean.toString(enabled));
        return getPublicOptions();
    }

    public void requirePublicRegistrationEnabled() {
        if (!publicRegistrationEnabled) {
            throw new AccessDeniedException("현재는 초대 링크로만 가입할 수 있습니다.");
        }
    }

    private boolean readBoolean(String key, boolean fallback) {
        try {
            String value = jdbcTemplate.query(
                    "select setting_value from " + SETTINGS_TABLE + " where setting_key = ?",
                    resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                    key
            );
            return value == null ? fallback : Boolean.parseBoolean(value);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private void persist(String key, String value) {
        int updated = jdbcTemplate.update(
                "update " + SETTINGS_TABLE + " set setting_value = ?, updated_at = current_timestamp where setting_key = ?",
                value,
                key
        );
        if (updated == 0) {
            try {
                jdbcTemplate.update(
                        "insert into " + SETTINGS_TABLE + " (setting_key, setting_value, updated_at) values (?, ?, current_timestamp)",
                        key,
                        value
                );
            } catch (RuntimeException exception) {
                jdbcTemplate.update(
                        "update " + SETTINGS_TABLE + " set setting_value = ?, updated_at = current_timestamp where setting_key = ?",
                        value,
                        key
                );
            }
        }
    }
}
