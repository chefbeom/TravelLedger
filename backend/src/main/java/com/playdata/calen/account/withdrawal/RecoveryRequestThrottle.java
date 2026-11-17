package com.playdata.calen.account.withdrawal;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded, shared recovery-request limits without persisting raw IP or email values. */
@Service
@Profile("withdrawal-integration")
@ConditionalOnProperty(prefix = "app.account-withdrawal",
        name = {"internal-workflow-enabled", "integration-http-enabled"}, havingValue = "true")
public class RecoveryRequestThrottle {
    private static final UUID FINGERPRINT_SCOPE = UUID.fromString("49e4a101-4958-4a34-a92c-c952f7e4d490");
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final int GLOBAL_LIMIT = 1000, IP_LIMIT = 60, EMAIL_LIMIT = 3, CLEANUP_BATCH = 250;
    private static final String UPSERT = """
            insert into withdrawal_recovery_request_limits (fingerprint, request_count, window_started_at, expires_at)
            values (?, 1, ?, ?)
            on duplicate key update
                request_count = case when expires_at <= ? then 1
                    when request_count >= 100000 then 100000 else request_count + 1 end,
                window_started_at = case when expires_at <= ? then ? else window_started_at end,
                expires_at = case when expires_at <= ? then ? else expires_at end
            """;
    private final JdbcTemplate jdbc;
    private final WithdrawalPayloadCipher cipher;
    private final Set<String> trustedProxies;

    public RecoveryRequestThrottle(JdbcTemplate jdbc, WithdrawalPayloadCipher cipher,
            @Value("${app.account-withdrawal.recovery-trusted-proxies:}") String trustedProxies) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.trustedProxies = trustedProxies == null || trustedProxies.isBlank() ? Set.of()
                : Arrays.stream(trustedProxies.split(",")).map(String::trim).map(RecoveryRequestThrottle::canonicalIp)
                        .collect(Collectors.toUnmodifiableSet());
    }

    /** Call before looking up an email. A shared global limit also bounds new fingerprint rows. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allowIp(HttpServletRequest request) {
        String ip = clientIp(request);
        LocalDateTime now = databaseNow();
        if (increment("RECOVERY_GLOBAL", "v1", now) > GLOBAL_LIMIT) return false;
        return increment("RECOVERY_IP", ip, now) <= IP_LIMIT;
    }

    /** Applied identically to existing and unknown email addresses. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allowEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 254) throw new ServiceUnavailableException("인증 요청을 확인할 수 없습니다.");
        return increment("RECOVERY_EMAIL", normalized, databaseNow()) <= EMAIL_LIMIT;
    }

    /** Only explicitly trusted peers may supply Nginx's overwritten X-Real-IP header. */
    String clientIp(HttpServletRequest request) {
        if (request == null) throw unavailable();
        String peer = canonicalIp(request.getRemoteAddr());
        if (!trustedProxies.contains(peer)) return peer;
        return canonicalIp(request.getHeader("X-Real-IP"));
    }

    private int increment(String namespace, String value, LocalDateTime now) {
        if (!cipher.isConfigured()) throw unavailable();
        String fingerprint = cipher.fingerprint(FINGERPRINT_SCOPE, namespace, value);
        LocalDateTime expires = now.plus(WINDOW);
        jdbc.update(UPSERT, fingerprint, now, expires, now, now, now, now, expires);
        Integer count = jdbc.queryForObject("select request_count from withdrawal_recovery_request_limits where fingerprint = ?",
                Integer.class, fingerprint);
        if (count == null || count < 1) throw unavailable();
        return count;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Transactional
    public void removeExpiredLimits() {
        LocalDateTime now = databaseNow();
        List<String> expired = jdbc.queryForList("select fingerprint from withdrawal_recovery_request_limits "
                + "where expires_at <= ? order by expires_at, fingerprint limit ?", String.class, now, CLEANUP_BATCH);
        for (String fingerprint : expired) {
            // A concurrent request may already have renewed the same fingerprint.
            jdbc.update("delete from withdrawal_recovery_request_limits where fingerprint = ? and expires_at <= ?", fingerprint, now);
        }
    }

    private LocalDateTime databaseNow() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp", (row, index) -> row.getObject(1, LocalDateTime.class));
        if (now == null) throw unavailable();
        return now;
    }

    private static String canonicalIp(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            String[] parts = value.split("\\.");
            int[] numbers = new int[4];
            for (int index = 0; index < 4; index++) {
                numbers[index] = Integer.parseInt(parts[index]);
                if (numbers[index] > 255) throw unavailable();
            }
            return numbers[0] + "." + numbers[1] + "." + numbers[2] + "." + numbers[3];
        }
        // A colon guarantees this is parsed as a literal, never a hostname/DNS lookup.
        if (!value.contains(":") || !value.matches("[0-9a-fA-F:.]{2,45}")) throw unavailable();
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch (Exception invalid) { throw unavailable(); }
    }

    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException("탈퇴 복구 요청의 보안 상태를 확인할 수 없습니다.");
    }
}
