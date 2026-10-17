package com.playdata.calen.travel.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.playdata.calen.travel.domain.model.TravelMediaData.*;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelMediaStorageAdapterTest {
    final TravelMediaStorageService sdk = mock(TravelMediaStorageService.class);
    final TravelMediaStorageAdapter adapter = new TravelMediaStorageAdapter(sdk);

    @Test void presignKeepsServerResolvedScopeAndAllOriginalAndThumbnailFields() {
        var candidate = new Candidate(" photo.jpg ", "image/jpeg", 21,
                List.of(new ThumbnailCandidate("480", "image/png", 5)));
        var wire = new TravelMediaStorageService.UploadCandidate(" photo.jpg ", "image/jpeg", 21,
                List.of(new TravelMediaStorageService.PreparedThumbnailUploadCandidate("480", "image/png", 5)));
        when(sdk.preparePresignedUploads(1L, 2L, 3L, List.of(wire))).thenReturn(List.of(
                new TravelMediaStorageService.PresignedUpload("PUT", "https://storage.invalid/original", "scoped/key", "stored.jpg",
                        "photo.jpg", "image/jpeg", 21, List.of(new TravelMediaStorageService.PresignedThumbnailUpload(
                                "480", "PUT", "https://storage.invalid/thumb", "scoped/thumb", "image/png", 5)))));

        assertThat(adapter.prepare(1L, 2L, 3L, List.of(candidate))).containsExactly(
                new Target("PUT", "https://storage.invalid/original", "scoped/key", "stored.jpg", "photo.jpg", "image/jpeg", 21,
                        List.of(new ThumbnailTarget("480", "PUT", "https://storage.invalid/thumb", "scoped/thumb", "image/png", 5))));
        verify(sdk).preparePresignedUploads(1L, 2L, 3L, List.of(wire));
        verifyNoMoreInteractions(sdk);
    }

    @Test void missingThumbnailListsRemainEmptyForTheLegacyStorageBoundary() {
        var wire = new TravelMediaStorageService.UploadCandidate("photo.jpg", "image/jpeg", 21, List.of());
        when(sdk.preparePresignedUploads(1L, 2L, 3L, List.of(wire))).thenReturn(List.of(
                new TravelMediaStorageService.PresignedUpload("PUT", "https://storage.invalid/upload", "key", "stored.jpg",
                        "photo.jpg", "image/jpeg", 21, null)));
        assertThat(adapter.prepare(1L, 2L, 3L, List.of(new Candidate("photo.jpg", "image/jpeg", 21, null)))
                .get(0).thumbnails()).isEmpty();
    }

    @Test void completionUsesVerifiedStorageMetadataNotClientDeclaredSizeAndGpsStaysOutsideTheSdk() {
        var file = new Completed("key", "photo.jpg", "image/jpeg", 21,
                List.of(new CompletedThumbnail("480", "thumb/key", "image/png", 5)), BigDecimal.ONE, BigDecimal.TEN);
        var wire = new TravelMediaStorageService.CompletedUpload("key", "photo.jpg", "image/jpeg", 21,
                List.of(new TravelMediaStorageService.CompletedPreparedThumbnailUpload("480", "thumb/key", "image/png", 5)));
        when(sdk.completePresignedUpload(1L, 2L, 3L, wire)).thenReturn(
                new TravelMediaStorageService.StoredTravelMedia("verified.jpg", "stored.jpg", "verified/key", "image/png", 19));
        assertThat(adapter.complete(1L, 2L, 3L, file)).isEqualTo(new Stored("verified.jpg", "stored.jpg", "verified/key", "image/png", 19));
        verify(sdk).completePresignedUpload(1L, 2L, 3L, wire);
    }

    @Test void completionWithoutThumbnailsAndDeletionKeepTheExistingSdkOperations() {
        when(sdk.supportsPresignedUpload()).thenReturn(true);
        when(sdk.completePresignedUpload(1L, 2L, 3L,
                new TravelMediaStorageService.CompletedUpload("key", "receipt.pdf", "application/pdf", 12, List.of())))
                .thenReturn(new TravelMediaStorageService.StoredTravelMedia("receipt.pdf", "receipt.pdf", "key", "application/pdf", 12));
        assertThat(adapter.supportsPresigned()).isTrue();
        assertThat(adapter.complete(1L, 2L, 3L, new Completed("key", "receipt.pdf", "application/pdf", 12, null, null, null)).fileSize()).isEqualTo(12);
        adapter.delete("key", "application/pdf");
        verify(sdk).deleteImageWithThumbnailsQuietly("key", "application/pdf");
    }
}
