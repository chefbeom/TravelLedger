package com.playdata.calen.travel.adapter.out.storage;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelRouteSegment;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.TravelDriveLinkService;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.transaction.support.TransactionSynchronization;

class TravelPlanAttachmentsAdapterTest {
    private final TravelMediaAssetRepository media = mock(TravelMediaAssetRepository.class);
    private final TravelRouteSegmentRepository routes = mock(TravelRouteSegmentRepository.class);
    private final TravelDriveLinkService drive = mock(TravelDriveLinkService.class);
    private final TravelMediaStorageService storage = mock(TravelMediaStorageService.class);
    private final TravelPlanAttachmentsAdapter adapter = new TravelPlanAttachmentsAdapter(mock(TravelPlanRepository.class), media, routes, drive,
            new TravelAttachmentCleanup(drive, storage, new ObjectMapper()));

    @Test void mediaCleanupCapturesImmutablePathsAndWaitsForCommit() {
        var asset = new TravelMediaAsset(); asset.setStoragePath("old/photo.jpg"); asset.setContentType("image/jpeg");
        when(media.findAllByPlanIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(2L, 1L)).thenReturn(List.of(asset));
        TransactionSynchronizationManager.initSynchronization();
        try {
            adapter.removeMedia(1L, 2L);
            verify(drive).removeMediaLinks(List.of(asset));
            verify(media).deleteAllByPlanId(2L);
            verifyNoInteractions(storage);
            asset.setStoragePath("new/unrelated.jpg"); asset.setContentType("application/pdf");
            TransactionSynchronizationUtils.triggerAfterCommit();
            verifyNoInteractions(storage);
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verify(storage).deleteImageWithThumbnailsQuietly("old/photo.jpg", "image/jpeg");
            verifyNoMoreInteractions(storage);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "null", "not-json", "[null,{}]"})
    void missingOrMalformedGpxMetadataKeepsExistingNoFileDeletionBehavior(String raw) {
        var route = new TravelRouteSegment(); route.setGpxFilesJson(raw);
        when(routes.findAllByPlanIdAndPlanOwnerIdOrderByRouteDateDescIdDesc(2L, 1L)).thenReturn(List.of(route));
        TransactionSynchronizationManager.initSynchronization();
        try {
            adapter.removeRoutes(1L, 2L);
            verify(drive).removeRouteGpxLinks(route);
            verify(routes).deleteAllByPlanId(2L);
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verifyNoInteractions(storage);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void gpxCleanupRetainsMetadataContractAndSkipsNullOrBlankPaths() {
        var route = new TravelRouteSegment();
        route.setGpxFilesJson("[null,{\"storagePath\":\" \"},{\"originalFileName\":\"route.gpx\",\"storagePath\":\"old/route.gpx\",\"contentType\":\"application/gpx+xml\",\"fileSize\":7}]");
        when(routes.findAllByPlanIdAndPlanOwnerIdOrderByRouteDateDescIdDesc(2L, 1L)).thenReturn(List.of(route));
        TransactionSynchronizationManager.initSynchronization();
        try {
            adapter.removeRoutes(1L, 2L);
            verifyNoInteractions(storage);
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verify(storage).deleteQuietly("old/route.gpx");
            verifyNoMoreInteractions(storage);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
}
