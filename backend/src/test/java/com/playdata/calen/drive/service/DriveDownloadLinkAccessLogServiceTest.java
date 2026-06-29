package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.drive.domain.DriveDownloadLinkAccessLog;
import com.playdata.calen.drive.repository.DriveDownloadLinkAccessLogRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DriveDownloadLinkAccessLogServiceTest {

    @Mock
    private DriveDownloadLinkAccessLogRepository driveDownloadLinkAccessLogRepository;

    @Test
    void recordStoresTokenFingerprintWithoutRawToken() {
        DriveDownloadLinkAccessLogService service = new DriveDownloadLinkAccessLogService(driveDownloadLinkAccessLogRepository);
        String rawToken = "public-token-secret";

        service.record(
                7L,
                11L,
                1L,
                rawToken,
                "success",
                new DriveDownloadLinkAccessLogService.AccessMetadata(
                        "192.0.2.10",
                        "Mozilla/5.0 " + "A".repeat(300)
                )
        );

        ArgumentCaptor<DriveDownloadLinkAccessLog> logCaptor = ArgumentCaptor.forClass(DriveDownloadLinkAccessLog.class);
        verify(driveDownloadLinkAccessLogRepository).save(logCaptor.capture());
        DriveDownloadLinkAccessLog log = logCaptor.getValue();

        assertThat(log.getLinkId()).isEqualTo(7L);
        assertThat(log.getItemId()).isEqualTo(11L);
        assertThat(log.getOwnerId()).isEqualTo(1L);
        assertThat(log.getStatus()).isEqualTo("success");
        assertThat(log.getTokenFingerprint()).hasSize(64);
        assertThat(log.getTokenFingerprint()).doesNotContain(rawToken);
        assertThat(log.getClientAddress()).isEqualTo("192.0.2.10");
        assertThat(log.getUserAgent()).hasSize(255);
    }

    @Test
    void listRecentLogsDoesNotExposeTokenFingerprint() {
        DriveDownloadLinkAccessLog log = new DriveDownloadLinkAccessLog();
        log.setId(3L);
        log.setLinkId(7L);
        log.setItemId(11L);
        log.setOwnerId(1L);
        log.setStatus("invalid");
        log.setTokenFingerprint("a".repeat(64));
        log.setClientAddress("192.0.2.10");
        log.setUserAgent("agent");
        when(driveDownloadLinkAccessLogRepository.findTop50ByLinkIdAndOwnerIdOrderByAccessedAtDesc(7L, 1L))
                .thenReturn(List.of(log));

        DriveDownloadLinkAccessLogService service = new DriveDownloadLinkAccessLogService(driveDownloadLinkAccessLogRepository);

        var response = service.listRecentLogs(1L, 7L).get(0);

        assertThat(response.linkId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo("invalid");
        assertThat(response.clientAddress()).isEqualTo("192.0.2.10");
        assertThat(response.toString()).doesNotContain("aaaaaaaa");
    }
}
