package com.playdata.calen.drive.service;

import com.playdata.calen.drive.repository.DriveDownloadLinkAccessLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded, restartable cleanup of pre-policy direct-share logs; public-link logs are untouched. */
@Service
@RequiredArgsConstructor
public class DriveDirectShareAuditAnonymizer {
    private static final int MAX_BATCH = 250;
    private final DriveDownloadLinkAccessLogRepository logs;

    @Transactional
    public int anonymizeBatch(int limit) {
        if (limit < 1 || limit > MAX_BATCH) throw new IllegalArgumentException("Invalid audit batch size.");
        var batch = logs.findDirectShareLogsToAnonymize("shared!_%", PageRequest.of(0, limit));
        for (var log : batch) {
            log.setClientAddress(null);
            log.setUserAgent(null);
            log.setTokenFingerprint(DriveDownloadLinkAccessLogService.ANONYMOUS_DIRECT_SHARE_FINGERPRINT);
            log.setDirectShareAnonymized(true);
        }
        logs.flush();
        return batch.size();
    }
}
