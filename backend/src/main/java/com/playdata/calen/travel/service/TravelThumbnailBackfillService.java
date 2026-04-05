package com.playdata.calen.travel.service;

import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class TravelThumbnailBackfillService {

    private static final String IMAGE_CONTENT_TYPE_PREFIX = "image/";

    private final TravelMediaAssetRepository travelMediaAssetRepository;
    private final TravelMediaStorageService travelMediaStorageService;

    private final AtomicBoolean backfillRunning = new AtomicBoolean(false);
    private final AtomicLong nextStartAfterId = new AtomicLong(0L);

    @Value("${app.travel.thumbnail-backfill-enabled:true}")
    private boolean thumbnailBackfillEnabled;

    @Value("${app.travel.thumbnail-backfill-page-size:25}")
    private int thumbnailBackfillPageSize;

    @Value("${app.travel.thumbnail-backfill-max-items-per-run:100}")
    private int thumbnailBackfillMaxItemsPerRun;

    @EventListener(ApplicationReadyEvent.class)
    public void runStartupThumbnailBackfill() {
        runBackfillPass("startup");
    }

    @Scheduled(
            fixedDelayString = "${app.travel.thumbnail-backfill-fixed-delay-ms:900000}",
            initialDelayString = "${app.travel.thumbnail-backfill-initial-delay-ms:300000}"
    )
    public void runScheduledThumbnailBackfill() {
        runBackfillPass("scheduled");
    }

    public BackfillRunSummary runBackfillPassNow() {
        return runBackfillPass("manual");
    }

    BackfillRunSummary runBackfillPass(String trigger) {
        if (!thumbnailBackfillEnabled) {
            return BackfillRunSummary.disabled(trigger);
        }
        if (!backfillRunning.compareAndSet(false, true)) {
            return BackfillRunSummary.skipped(trigger, nextStartAfterId.get());
        }

        long cursor = Math.max(0L, nextStartAfterId.get());
        int pageSize = Math.max(1, thumbnailBackfillPageSize);
        int maxItemsPerRun = Math.max(1, thumbnailBackfillMaxItemsPerRun);
        int examinedCount = 0;
        int createdCount = 0;
        int alreadyPresentCount = 0;
        int failedCount = 0;
        int skippedCount = 0;

        try {
            while (examinedCount < maxItemsPerRun) {
                int requestSize = Math.min(pageSize, maxItemsPerRun - examinedCount);
                List<TravelMediaAsset> batch = travelMediaAssetRepository
                        .findAllByIdGreaterThanAndContentTypeStartingWithOrderByIdAsc(
                                cursor,
                                IMAGE_CONTENT_TYPE_PREFIX,
                                PageRequest.of(0, requestSize)
                        )
                        .getContent();

                if (batch.isEmpty()) {
                    cursor = 0L;
                    break;
                }

                for (TravelMediaAsset mediaAsset : batch) {
                    if (mediaAsset == null) {
                        continue;
                    }

                    Long mediaId = mediaAsset.getId();
                    if (mediaId != null && mediaId > cursor) {
                        cursor = mediaId;
                    }

                    examinedCount += 1;
                    TravelMediaStorageService.ThumbnailPreparationStatus status =
                            travelMediaStorageService.ensurePreparedThumbnails(
                                    mediaAsset.getStoragePath(),
                                    mediaAsset.getContentType()
                            );

                    switch (status) {
                        case CREATED -> createdCount += 1;
                        case ALREADY_PRESENT -> alreadyPresentCount += 1;
                        case FAILED -> failedCount += 1;
                        case SKIPPED -> skippedCount += 1;
                        default -> {
                        }
                    }

                    if (examinedCount >= maxItemsPerRun) {
                        break;
                    }
                }

                if (batch.size() < requestSize) {
                    cursor = 0L;
                    break;
                }
            }
        } finally {
            nextStartAfterId.set(cursor);
            backfillRunning.set(false);
        }

        BackfillRunSummary summary = new BackfillRunSummary(
                trigger,
                examinedCount,
                createdCount,
                alreadyPresentCount,
                failedCount,
                skippedCount,
                cursor,
                false,
                false
        );
        if (summary.examinedCount() > 0 || summary.createdCount() > 0 || summary.failedCount() > 0) {
            log.info(
                    "Travel thumbnail backfill {} examined={}, created={}, alreadyPresent={}, failed={}, skipped={}, nextStartAfterId={}",
                    summary.trigger(),
                    summary.examinedCount(),
                    summary.createdCount(),
                    summary.alreadyPresentCount(),
                    summary.failedCount(),
                    summary.skippedCount(),
                    summary.nextStartAfterId()
            );
        }
        return summary;
    }

    public record BackfillRunSummary(
            String trigger,
            int examinedCount,
            int createdCount,
            int alreadyPresentCount,
            int failedCount,
            int skippedCount,
            long nextStartAfterId,
            boolean disabled,
            boolean skippedBecauseRunning
    ) {

        private static BackfillRunSummary disabled(String trigger) {
            return new BackfillRunSummary(trigger, 0, 0, 0, 0, 0, 0L, true, false);
        }

        private static BackfillRunSummary skipped(String trigger, long nextStartAfterId) {
            return new BackfillRunSummary(trigger, 0, 0, 0, 0, 0, nextStartAfterId, false, true);
        }
    }
}
