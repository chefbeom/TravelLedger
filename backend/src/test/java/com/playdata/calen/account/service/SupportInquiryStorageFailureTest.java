package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

class SupportInquiryStorageFailureTest {
    @TempDir Path root;

    @Test void partialAttachmentIsRemovedWhenCopyFails() throws Exception {
        var service = new SupportInquiryStorageService();
        ReflectionTestUtils.setField(service, "attachmentStoragePath", root.toString());
        service.initialize();
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(16L);
        when(file.getOriginalFilename()).thenReturn("receipt.png");
        when(file.getContentType()).thenReturn("image/png");
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        InputStream failing = new InputStream() {
            private int count;
            @Override public int read() throws IOException {
                if (count++ == 0) return 1;
                throw new IOException("simulated copy error");
            }
        };
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(header), failing);
        assertThatThrownBy(() -> service.store(7L, file)).isInstanceOf(IllegalStateException.class);
        try (var paths = Files.walk(root)) {
            assertThat(paths.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }

    @Test void partialAttachmentIsRemovedWhenTheInputThrowsAnUncheckedFailure() throws Exception {
        var service = new SupportInquiryStorageService();
        ReflectionTestUtils.setField(service, "attachmentStoragePath", root.toString());
        service.initialize();
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(16L);
        when(file.getOriginalFilename()).thenReturn("receipt.png");
        when(file.getContentType()).thenReturn("image/png");
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        InputStream failing = new InputStream() {
            private int count;
            @Override public int read() {
                if (count++ == 0) return 1;
                throw new IllegalStateException("simulated unchecked failure");
            }
        };
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(header), failing);
        assertThatThrownBy(() -> service.store(7L, file)).isInstanceOf(IllegalStateException.class);
        try (var paths = Files.walk(root)) {
            assertThat(paths.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }
}
