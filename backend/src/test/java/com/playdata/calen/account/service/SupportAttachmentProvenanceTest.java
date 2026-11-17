package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class SupportAttachmentProvenanceTest {
    @TempDir Path root;
    private static final String ID = "test_storage_identifier_001";
    private final byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2};

    @Test void newUploadsRecordDigestAndOnlyAVerifiedStorageIdentity() throws Exception {
        var service = service();
        Files.writeString(root.resolve(".calen-support-storage-id"), ID);
        ReflectionTestUtils.setField(service, "attachmentStorageId", ID);
        var result = service.store(7L, new MockMultipartFile("image", "receipt.png", "image/png", png));
        assertThat(result.storageId()).isEqualTo(ID);
        assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)));
        assertThat(Files.readAllBytes(root.resolve(result.storagePath()))).isEqualTo(png);
    }

    @Test void unspecifiedIdentityPreservesUploadsButDoesNotInventProvenance() {
        var result = service().store(7L, new MockMultipartFile("image", "receipt.png", "image/png", png));
        assertThat(result.storageId()).isNull();
        assertThat(result.sha256()).hasSize(64);
    }

    @Test void configuredIdentityWithoutItsMarkerDoesNotCreateAnAttachment() throws Exception {
        var service = service(); ReflectionTestUtils.setField(service, "attachmentStorageId", ID);
        assertThatThrownBy(() -> service.store(7L, new MockMultipartFile("image", "receipt.png", "image/png", png)))
                .isInstanceOf(IllegalStateException.class);
        try (var paths = Files.list(root)) { assertThat(paths.toList()).isEmpty(); }
    }

    private SupportInquiryStorageService service() {
        var service = new SupportInquiryStorageService();
        ReflectionTestUtils.setField(service, "attachmentStoragePath", root.toString());
        service.initialize(); return service;
    }
}
