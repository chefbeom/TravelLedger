package com.playdata.calen.ledger.adapter.in.image;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.ledger.domain.model.LedgerImageData.ImageReadFailure;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class MultipartLedgerImageContentTest {
    @Test void storageBridgeForwardsMetadataAndTheOriginalStreamWithoutReadingBytes() throws Exception {
        var file=mock(MultipartFile.class); var stream=new java.io.ByteArrayInputStream(new byte[]{1,2});
        when(file.getOriginalFilename()).thenReturn("receipt.jpg"); when(file.getContentType()).thenReturn("image/jpeg");
        when(file.getSize()).thenReturn(2L); when(file.getInputStream()).thenReturn(stream);
        var content=new MultipartLedgerImageContent(file); verifyNoInteractions(file);
        assertThat(content.fileName()).isEqualTo("receipt.jpg"); assertThat(content.contentType()).isEqualTo("image/jpeg");
        assertThat(content.size()).isEqualTo(2); assertThat(content.empty()).isFalse(); assertThat(content.openStream()).isSameAs(stream);
        verify(file,never()).getBytes();
    }
    @Test void bridgeDoesNotReadOrCopyBeforeTheProviderNeedsBytes() throws Exception {
        var file = mock(MultipartFile.class); byte[] bytes = {1,2,3};
        when(file.getBytes()).thenReturn(bytes); when(file.getContentType()).thenReturn("image/png");
        var content = new MultipartLedgerImageContent(file); verifyNoInteractions(file);
        assertThat(content.contentType()).isEqualTo("image/png"); verify(file,never()).getBytes();
        assertThat(content.bytes()).isSameAs(bytes); verify(file).getBytes();
    }

    @Test void inputReadFailureRetainsCauseForTransportErrorHandling() throws Exception {
        var file = mock(MultipartFile.class); var failure = new IOException("test-only image read failure");
        when(file.getBytes()).thenThrow(failure);
        assertThatThrownBy(() -> new MultipartLedgerImageContent(file).bytes()).isInstanceOf(ImageReadFailure.class).hasCause(failure);
    }
}
