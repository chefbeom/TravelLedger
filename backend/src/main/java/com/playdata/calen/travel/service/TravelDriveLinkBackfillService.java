package com.playdata.calen.travel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelRouteSegment;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.repository.TravelRouteSegmentRepository;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "app.travel.drive-link-backfill", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TravelDriveLinkBackfillService {

    private static final String JOB_KEY = "travel-drive-link-backfill-20260707";
    private static final int MAX_NOTE_LENGTH = 500;

    private final TravelMediaAssetRepository travelMediaAssetRepository;
    private final TravelRouteSegmentRepository travelRouteSegmentRepository;
    private final TravelDriveLinkService travelDriveLinkService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void runStartupBackfill() {
        ensureJobTable();
        if (isCompleted()) {
            log.info("Travel drive link backfill already completed. jobKey={}", JOB_KEY);
            return;
        }

        int mediaCount = 0;
        for (TravelMediaAsset asset : travelMediaAssetRepository.findAll()) {
            travelDriveLinkService.linkMediaAsset(asset);
            mediaCount += 1;
        }

        int routeCount = 0;
        int gpxFileCount = 0;
        for (TravelRouteSegment routeSegment : travelRouteSegmentRepository.findAll()) {
            List<RouteGpxFile> files = deserializeRouteGpxFiles(routeSegment.getGpxFilesJson());
            if (files.isEmpty()) {
                continue;
            }
            routeCount += 1;
            gpxFileCount += files.size();
            travelDriveLinkService.replaceRouteGpxLinks(routeSegment, files.stream()
                    .map(file -> new TravelDriveLinkService.TravelLinkedFile(
                            file.originalFileName(),
                            file.storagePath(),
                            file.contentType(),
                            file.fileSize()
                    ))
                    .toList());
        }

        markCompleted("media=" + mediaCount + ", routes=" + routeCount + ", gpxFiles=" + gpxFileCount);
        log.info(
                "Travel drive link backfill completed. media={}, routes={}, gpxFiles={}",
                mediaCount,
                routeCount,
                gpxFileCount
        );
    }

    private void ensureJobTable() {
        jdbcTemplate.execute("""
                create table if not exists app_one_time_jobs (
                    job_key varchar(160) primary key,
                    completed_at timestamp not null,
                    note varchar(500) null
                )
                """);
    }

    private boolean isCompleted() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from app_one_time_jobs where job_key = ?",
                Integer.class,
                JOB_KEY
        );
        return count != null && count > 0;
    }

    private void markCompleted(String note) {
        try {
            jdbcTemplate.update(
                    "insert into app_one_time_jobs (job_key, completed_at, note) values (?, CURRENT_TIMESTAMP, ?)",
                    JOB_KEY,
                    truncate(note)
            );
        } catch (DuplicateKeyException exception) {
            log.info("Travel drive link backfill completion row already exists. jobKey={}", JOB_KEY);
        }
    }

    private List<RouteGpxFile> deserializeRouteGpxFiles(String gpxFilesJson) {
        if (!StringUtils.hasText(gpxFilesJson)) {
            return Collections.emptyList();
        }
        try {
            List<RouteGpxFile> files = objectMapper.readValue(gpxFilesJson, new TypeReference<>() {
            });
            return files == null ? Collections.emptyList() : files.stream()
                    .filter(file -> file != null && StringUtils.hasText(file.storagePath()))
                    .toList();
        } catch (JsonProcessingException exception) {
            log.warn("Failed to parse route GPX metadata during travel-drive backfill.");
            return Collections.emptyList();
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_NOTE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_NOTE_LENGTH);
    }

    private record RouteGpxFile(
            String originalFileName,
            String storagePath,
            String contentType,
            long fileSize
    ) {
    }
}