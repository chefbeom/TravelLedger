package com.playdata.calen.travel.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.playdata.calen.travel.domain.model.TravelMediaData.Download;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.*;
import org.springframework.http.*;

class TravelMediaHttpResponsesTest {
    final TravelMediaStorageService storage = mock(TravelMediaStorageService.class);
    final TravelMediaHttpResponses responses = new TravelMediaHttpResponses(storage);

    @ParameterizedTest
    @CsvSource({"image/jpeg,image/jpeg", "image/png,image/png", "image/webp,image/png", "image/gif,image/png", "application/pdf,image/jpeg"})
    void thumbnailHeadersBytesAndResourceClosureMatchTheExistingRenderer(String originalType, String thumbnailType) {
        var closed = new AtomicBoolean();
        byte[] bytes = {1, 2, 3};
        var input = new ByteArrayInputStream(bytes) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        };
        var download = new Download("test-only/key", originalType, "여행 사진.jpg");
        when(storage.loadThumbnail(download.storagePath(), originalType, 480)).thenReturn(
                new TravelMediaStorageService.PreparedThumbnail(new InputStreamResource(input), "ignored/sdk-type"));

        var response = responses.render(download, true, 480);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType(thumbnailType));
        assertThat(response.getHeaders().getContentLength()).isEqualTo(bytes.length);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("public, max-age=86400");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo(download.fileName());
        assertThat(response.getHeaders().getContentDisposition().getType()).isEqualTo(originalType.equals("application/pdf") ? "attachment" : "inline");
        assertThat(((ByteArrayResource) response.getBody()).getByteArray()).isEqualTo(bytes);
        assertThat(closed).isTrue();
        verify(storage, never()).loadAsResource(any());
    }

    @Test void originalPdfIsAnAttachmentWithUtf8NameAndNoNewCacheHeader() {
        var resource = new ByteArrayResource("synthetic pdf".getBytes(StandardCharsets.UTF_8));
        when(storage.loadAsResource("test-only/pdf")).thenReturn(resource);
        var response = responses.render(new Download("test-only/pdf", "application/pdf", "영수증.pdf"), false, null);
        assertThat(response.getBody()).isSameAs(resource);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("영수증.pdf");
        assertThat(response.getHeaders().getCacheControl()).isNull();
        verify(storage, never()).loadThumbnail(any(), any(), any());
    }

    @Test void genericThumbnailFailureStillFallsBackToOriginalButLoginPreviewNeverDoes() throws IOException {
        var download = new Download("test-only/key", "image/jpeg", " ");
        var broken = mock(Resource.class);
        when(broken.getInputStream()).thenThrow(new IOException("synthetic unreadable thumbnail"));
        when(storage.loadThumbnail(download.storagePath(), download.contentType(), null)).thenReturn(
                new TravelMediaStorageService.PreparedThumbnail(broken, "image/jpeg"));
        var original = new ByteArrayResource(new byte[] {4});
        when(storage.loadAsResource(download.storagePath())).thenReturn(original);
        var generic = responses.render(download, true, null);
        assertThat(generic.getBody()).isSameAs(original);
        assertThat(generic.getHeaders().getContentDisposition().getFilename()).isEqualTo("media");
        assertThat(generic.getHeaders().getCacheControl()).isNull();
        clearInvocations(storage);

        var preview = responses.thumbnailOnly(download, null);
        assertThat(preview.getStatusCode().value()).isEqualTo(404);
        assertThat(preview.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(preview.getBody()).isNull();
        verify(storage, never()).loadAsResource(any());
    }

    @Test void missingOrRuntimeFailingPreviewIsNotFoundWithoutOriginalFallback() {
        var download = new Download("test-only/key", "image/jpeg", null);
        assertThat(responses.thumbnailOnly(download, 240).getStatusCode().value()).isEqualTo(404);
        when(storage.loadThumbnail(download.storagePath(), download.contentType(), 240)).thenThrow(new IllegalStateException("synthetic unavailable"));
        assertThat(responses.thumbnailOnly(download, 240).getStatusCode().value()).isEqualTo(404);
        verify(storage, never()).loadAsResource(any());
    }

    @Test void successfulLoginPreviewIsNoStoreAndHasNoDispositionHeader() {
        var download = new Download("test-only/key", "image/webp", "photo.webp");
        when(storage.loadThumbnail(download.storagePath(), download.contentType(), 240)).thenReturn(
                new TravelMediaStorageService.PreparedThumbnail(new ByteArrayResource(new byte[] {1}), "image/jpeg"));
        var response = responses.thumbnailOnly(download, 240);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
