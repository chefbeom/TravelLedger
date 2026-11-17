package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.drive.domain.DriveDownloadLinkAccessLog;
import com.playdata.calen.drive.repository.DriveDownloadLinkAccessLogRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.drive.direct-share-audit-anonymization.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:drive-audit-privacy-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false"
})
class DriveDirectShareAuditAnonymizationIntegrationTest {
    @Autowired DriveDownloadLinkAccessLogRepository logs;
    @Autowired DriveDirectShareAuditAnonymizer anonymizer;
    @Autowired DriveDownloadLinkAccessLogService writer;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void clearIsolatedAuditTable() { logs.deleteAllInBatch(); }

    @Test void batchesAnonymizeOnlyLegacyDirectSharesAndPreserveStatusTimeAndFileScope() {
        var first = legacy(null, "shared_success");
        var second = legacy(null, "shared_permission_denied");
        var third = legacy(null, "shared_not_found");
        var publicLink = legacy(55L, "success");
        var failedPublicLink = legacy(null, "invalid");
        var similarStatus = legacy(null, "sharedXnot-a-direct-share");

        assertThat(anonymizer.anonymizeBatch(2)).isEqualTo(2);
        assertThat(anonymizer.anonymizeBatch(2)).isEqualTo(1);
        assertThat(anonymizer.anonymizeBatch(2)).isZero();

        for (var original : java.util.List.of(first, second, third)) {
            var actual = logs.findById(original.getId()).orElseThrow();
            assertThat(actual.isDirectShareAnonymized()).isTrue();
            assertThat(actual.getTokenFingerprint()).isEqualTo(DriveDownloadLinkAccessLogService.ANONYMOUS_DIRECT_SHARE_FINGERPRINT);
            assertThat(actual.getClientAddress()).isNull(); assertThat(actual.getUserAgent()).isNull();
            assertThat(actual.getStatus()).isEqualTo(original.getStatus());
            assertThat(actual.getAccessedAt()).isEqualTo(original.getAccessedAt());
            assertThat(actual.getOwnerId()).isEqualTo(original.getOwnerId());
            assertThat(actual.getItemId()).isEqualTo(original.getItemId());
        }
        for (var untouched : java.util.List.of(publicLink, failedPublicLink, similarStatus)) {
            var actual = logs.findById(untouched.getId()).orElseThrow();
            assertThat(actual.isDirectShareAnonymized()).isFalse();
            assertThat(actual.getTokenFingerprint()).isEqualTo(untouched.getTokenFingerprint());
            assertThat(actual.getClientAddress()).isEqualTo("192.0.2.1");
            assertThat(actual.getUserAgent()).isEqualTo("private-agent");
        }
    }

    @Test void failedTransactionRollsBackAndALateLegacyWriterIsPickedUp() {
        var original = legacy(null, "shared_success");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(anonymizer.anonymizeBatch(1)).isEqualTo(1);
            throw new IllegalStateException("simulate rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(logs.findById(original.getId()).orElseThrow().getClientAddress()).isEqualTo("192.0.2.1");
        assertThat(anonymizer.anonymizeBatch(1)).isEqualTo(1);
        var late = legacy(null, "shared_success");
        assertThat(anonymizer.anonymizeBatch(1)).isEqualTo(1);
        assertThat(logs.findById(late.getId()).orElseThrow().isDirectShareAnonymized()).isTrue();
    }

    @Test void newDirectShareWritesAreAnonymousBeforeAnyBackgroundCleanup() {
        writer.recordDirectShareAccess(12L, 34L, 56L, 78L, "success",
                new DriveDownloadLinkAccessLogService.AccessMetadata("198.51.100.1", "browser"));
        assertThat(logs.findAll()).singleElement().satisfies(log -> {
            assertThat(log.isDirectShareAnonymized()).isTrue();
            assertThat(log.getClientAddress()).isNull(); assertThat(log.getUserAgent()).isNull();
            assertThat(log.getTokenFingerprint()).isEqualTo(DriveDownloadLinkAccessLogService.ANONYMOUS_DIRECT_SHARE_FINGERPRINT);
            assertThat(log.getItemId()).isEqualTo(34L); assertThat(log.getOwnerId()).isEqualTo(56L);
        });
        assertThat(anonymizer.anonymizeBatch(1)).isZero();
    }

    @Test void concurrentBatchesNeverExposeOrOverwriteUnrelatedLogs() throws Exception {
        for (int i = 0; i < 8; i++) legacy(null, "shared_success");
        var publicLog = legacy(10L, "success");
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return anonymizer.anonymizeBatch(8); });
            var second = pool.submit(() -> { start.await(); return anonymizer.anonymizeBatch(8); });
            start.countDown(); first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertThat(anonymizer.anonymizeBatch(8)).isZero();
            assertThat(logs.findAll().stream().filter(log -> "shared_success".equals(log.getStatus())))
                    .hasSize(8).allSatisfy(log -> {
                        assertThat(log.isDirectShareAnonymized()).isTrue();
                        assertThat(log.getClientAddress()).isNull();
                    });
            assertThat(logs.findById(publicLog.getId()).orElseThrow().getClientAddress()).isEqualTo("192.0.2.1");
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    private DriveDownloadLinkAccessLog legacy(Long linkId, String status) {
        var log = new DriveDownloadLinkAccessLog();
        log.setLinkId(linkId); log.setItemId(300L); log.setOwnerId(400L); log.setStatus(status);
        log.setTokenFingerprint(UUID.randomUUID().toString());
        log.setClientAddress("192.0.2.1"); log.setUserAgent("private-agent");
        log.setAccessedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        return logs.saveAndFlush(log);
    }
}
