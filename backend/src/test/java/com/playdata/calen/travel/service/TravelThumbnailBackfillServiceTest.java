package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class TravelThumbnailBackfillServiceTest {

    @Test
    void runBackfillPassNowProcessesConfiguredImageBatchAndCountsStatuses() {
        TravelMediaAssetRepository travelMediaAssetRepository = mock(TravelMediaAssetRepository.class);
        TravelMediaStorageService travelMediaStorageService = mock(TravelMediaStorageService.class);
        TravelThumbnailBackfillService service = new TravelThumbnailBackfillService(
                travelMediaAssetRepository,
                travelMediaStorageService
        );
        ReflectionTestUtils.setField(service, "thumbnailBackfillEnabled", true);
        ReflectionTestUtils.setField(service, "thumbnailBackfillPageSize", 2);
        ReflectionTestUtils.setField(service, "thumbnailBackfillMaxItemsPerRun", 2);

        TravelMediaAsset firstAsset = createAsset(11L, "travel-media/1.jpg", "image/jpeg");
        TravelMediaAsset secondAsset = createAsset(12L, "travel-media/2.jpg", "image/jpeg");

        when(travelMediaAssetRepository.findAllByIdGreaterThanAndContentTypeStartingWithOrderByIdAsc(eq(0L), eq("image/"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(firstAsset, secondAsset)));
        when(travelMediaStorageService.ensurePreparedThumbnails(firstAsset.getStoragePath(), firstAsset.getContentType()))
                .thenReturn(TravelMediaStorageService.ThumbnailPreparationStatus.CREATED);
        when(travelMediaStorageService.ensurePreparedThumbnails(secondAsset.getStoragePath(), secondAsset.getContentType()))
                .thenReturn(TravelMediaStorageService.ThumbnailPreparationStatus.ALREADY_PRESENT);

        TravelThumbnailBackfillService.BackfillRunSummary summary = service.runBackfillPassNow();

        assertThat(summary.disabled()).isFalse();
        assertThat(summary.skippedBecauseRunning()).isFalse();
        assertThat(summary.examinedCount()).isEqualTo(2);
        assertThat(summary.createdCount()).isEqualTo(1);
        assertThat(summary.alreadyPresentCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isZero();
        assertThat(summary.skippedCount()).isZero();
        assertThat(summary.nextStartAfterId()).isEqualTo(12L);
    }

    @Test
    void runBackfillPassNowSkipsRepositoryWhenDisabled() {
        TravelMediaAssetRepository travelMediaAssetRepository = mock(TravelMediaAssetRepository.class);
        TravelMediaStorageService travelMediaStorageService = mock(TravelMediaStorageService.class);
        TravelThumbnailBackfillService service = new TravelThumbnailBackfillService(
                travelMediaAssetRepository,
                travelMediaStorageService
        );
        ReflectionTestUtils.setField(service, "thumbnailBackfillEnabled", false);

        TravelThumbnailBackfillService.BackfillRunSummary summary = service.runBackfillPassNow();

        assertThat(summary.disabled()).isTrue();
        verify(travelMediaAssetRepository, never()).findAllByIdGreaterThanAndContentTypeStartingWithOrderByIdAsc(any(Long.class), any(String.class), any(Pageable.class));
    }

    private TravelMediaAsset createAsset(Long id, String storagePath, String contentType) {
        TravelMediaAsset asset = new TravelMediaAsset();
        asset.setId(id);
        asset.setStoragePath(storagePath);
        asset.setContentType(contentType);
        return asset;
    }
}
