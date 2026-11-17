package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

class RecoveryRequestThrottleTest {
    private DriverManagerDataSource db;
    private JdbcTemplate jdbc;
    private WithdrawalPayloadCipher cipher;
    private RecoveryRequestThrottle throttle;

    @BeforeEach void setup() {
        db = new DriverManagerDataSource();
        db.setDriverClassName("org.h2.Driver");
        db.setUrl("jdbc:h2:mem:recovery-limit-" + UUID.randomUUID() + ";MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1");
        db.setUsername("sa"); db.setPassword("");
        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261105_056__withdrawal_recovery_request_limits.sql"));
        DatabasePopulatorUtils.execute(migration, db);
        DatabasePopulatorUtils.execute(migration, db);
        jdbc = new JdbcTemplate(db);
        cipher = new WithdrawalPayloadCipher("test-withdrawal-secret-at-least-32-characters");
        throttle = new RecoveryRequestThrottle(jdbc, cipher, "");
    }

    @Test void limitsMailAndIpWithoutStoringRawIdentifiers() {
        var request = request("198.51.100.9", "203.0.113.7");
        for (int index = 0; index < 3; index++) assertThat(throttle.allowEmail("Person@Example.test")).isTrue();
        assertThat(throttle.allowEmail("person@example.test")).isFalse();
        assertThat(throttle.allowEmail("another@example.test")).isTrue();
        for (int index = 0; index < 60; index++) assertThat(throttle.allowIp(request)).isTrue();
        assertThat(throttle.allowIp(request)).isFalse();
        assertThat(jdbc.queryForList("select fingerprint from withdrawal_recovery_request_limits", String.class))
                .allSatisfy(value -> assertThat(value).hasSize(43).doesNotContain("198.51", "example", "person"));
    }

    @Test void forwardedAddressIsIgnoredUnlessThePeerIsExplicitlyTrusted() {
        var request = request("198.51.100.9", "203.0.113.7");
        assertThat(throttle.clientIp(request)).isEqualTo("198.51.100.9");
        var trusted = new RecoveryRequestThrottle(jdbc, cipher, "198.51.100.9");
        assertThat(trusted.clientIp(request)).isEqualTo("203.0.113.7");
        request.addHeader("X-Forwarded-For", "192.0.2.1");
        assertThat(trusted.clientIp(request)).isEqualTo("203.0.113.7");
        assertThatThrownBy(() -> trusted.clientIp(request("198.51.100.9", "spoofed.example")))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test void expiredFingerprintsAreRemovedAndLimitsRestart() {
        assertThat(throttle.allowEmail("person@example.test")).isTrue();
        jdbc.update("update withdrawal_recovery_request_limits set expires_at = ?", LocalDateTime.now().minusMinutes(1));
        throttle.removeExpiredLimits();
        assertThat(jdbc.queryForObject("select count(*) from withdrawal_recovery_request_limits", Integer.class)).isZero();
        assertThat(throttle.allowEmail("person@example.test")).isTrue();
    }

    @Test void globalLimitStopsCreatingArbitraryIpFingerprints() {
        String global = cipher.fingerprint(UUID.fromString("49e4a101-4958-4a34-a92c-c952f7e4d490"), "RECOVERY_GLOBAL", "v1");
        jdbc.update("insert into withdrawal_recovery_request_limits (fingerprint, request_count, window_started_at, expires_at) values (?, 1000, ?, ?)",
                global, LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));
        assertThat(throttle.allowIp(request("198.51.100.9", null))).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from withdrawal_recovery_request_limits", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentNodesShareAnAtomicEmailLimit() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(db));
        try {
            List<java.util.concurrent.Future<Boolean>> calls = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                calls.add(pool.submit(() -> transaction.execute(status -> throttle.allowEmail("person@example.test"))));
            }
            int allowed = 0;
            for (var call : calls) if (Boolean.TRUE.equals(call.get(15, TimeUnit.SECONDS))) allowed++;
            assertThat(allowed).isEqualTo(3);
            assertThat(jdbc.queryForObject("select request_count from withdrawal_recovery_request_limits", Integer.class)).isEqualTo(12);
        } finally { pool.shutdownNow(); }
    }

    @Test void missingFingerprintKeyFailsClosed() {
        var missing = new RecoveryRequestThrottle(jdbc, new WithdrawalPayloadCipher(""), "");
        assertThatThrownBy(() -> missing.allowIp(request("198.51.100.9", null))).isInstanceOf(ServiceUnavailableException.class);
        assertThat(jdbc.queryForObject("select count(*) from withdrawal_recovery_request_limits", Integer.class)).isZero();
    }

    private MockHttpServletRequest request(String peer, String realIp) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(peer);
        if (realIp != null) request.addHeader("X-Real-IP", realIp);
        return request;
    }
}
