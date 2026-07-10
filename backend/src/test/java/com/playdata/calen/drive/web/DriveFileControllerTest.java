package com.playdata.calen.drive.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.drive.service.DriveDownloadLinkService;
import com.playdata.calen.drive.service.DriveService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class DriveFileControllerTest {

    private final DriveService driveService = mock(DriveService.class);
    private final DriveDownloadLinkService driveDownloadLinkService = mock(DriveDownloadLinkService.class);
    private final DriveFileController controller = new DriveFileController(driveService, driveDownloadLinkService);
    private final AppUserPrincipal principal = new AppUserPrincipal(
            1L,
            "user",
            "User",
            "hash",
            AppUserRole.USER,
            true
    );

    @Test
    void executableImageAndTextFormatsAreForcedToDownload() {
        when(driveService.downloadFile(1L, 10L)).thenReturn(
                new DriveService.DriveFilePayload(
                        "<svg><script>alert(1)</script></svg>".getBytes(),
                        "image/svg+xml",
                        "payload.svg",
                        38L
                )
        );

        ResponseEntity<byte[]> response = controller.download(principal, 10L);

        assertThat(response.getHeaders().getContentDisposition().getType()).isEqualTo("attachment");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isEqualTo("sandbox");
    }

    @Test
    void rasterImagesRemainInlineWithSecurityHeaders() {
        when(driveService.downloadFile(1L, 11L)).thenReturn(
                new DriveService.DriveFilePayload(new byte[] {1, 2, 3}, "image/png", "photo.png", 3L)
        );

        ResponseEntity<byte[]> response = controller.download(principal, 11L);

        assertThat(response.getHeaders().getContentDisposition().getType()).isEqualTo("inline");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
