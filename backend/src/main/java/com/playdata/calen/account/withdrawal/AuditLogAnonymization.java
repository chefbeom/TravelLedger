package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.LoginAuditLog;
import com.playdata.calen.account.domain.LoginAuditStatus;
import java.util.Set;
import java.util.regex.Pattern;

/** An allowlist prevents an audit detail, filename, IP, or user-controlled text from surviving as a type. */
public final class AuditLogAnonymization {
    public static final String ANONYMOUS = "익명";
    private static final Set<String> ADMIN_TYPES = Set.of(
            "ADMIN_ROLE_GRANT", "USER_ACTIVE_UPDATE", "DRIVE_USER_STATUS_UPDATE", "DRIVE_STORAGE_CAPACITY_UPDATE",
            "REGISTRATION_POLICY_UPDATE", "LOGIN_MAP_PREVIEW_UPDATE", "AI_CONTROL_UPDATE", "AI_ROUTING_UPDATE",
            "AI_CONTROL_PRESET_SECRET_UPDATE", "AI_CONTROL_PRESET_SECRET_DELETE", "DATA_STORAGE_CONTROL_UPDATE",
            "DATA_BACKUP_CREATE", "MINIO_BACKUP_CREATE", "DATA_BACKUP_DOWNLOAD", "DATA_RESTORE",
            "DATA_RESTORE_UPLOAD", "BLOCKED_IP_CLEAR", "IMAGE_ANALYSIS_CONCURRENCY_UPDATE"
    );
    private static final Pattern TARGET_DETAIL = Pattern.compile(
            "^(?:ADMIN_ROLE_GRANT:userId=([1-9][0-9]{0,18})|(?:USER_ACTIVE_UPDATE|DRIVE_USER_STATUS_UPDATE):userId=([1-9][0-9]{0,18}),active=(?:true|false))$");

    private AuditLogAnonymization() {}

    public static String eventType(LoginAuditStatus status, String detail) {
        if (status != LoginAuditStatus.ADMIN_ACTION || detail == null) return null;
        int separator = detail.indexOf(':');
        String type = separator < 0 ? detail : detail.substring(0, separator);
        return ADMIN_TYPES.contains(type) ? type : null;
    }

    public static Long targetUserId(LoginAuditStatus status, String detail) {
        if (status != LoginAuditStatus.ADMIN_ACTION || detail == null) return null;
        var match = TARGET_DETAIL.matcher(detail);
        if (!match.matches()) return null;
        try {
            return Long.valueOf(match.group(1) == null ? match.group(2) : match.group(1));
        } catch (NumberFormatException invalidId) {
            return null;
        }
    }

    public static void anonymizeActor(LoginAuditLog log) {
        log.setDetail(eventType(log.getStatus(), log.getDetail()));
        log.setLoginId(ANONYMOUS);
        log.setClientIp(ANONYMOUS);
        log.setUserAgent(null);
        log.setAppUser(null);
    }
}
