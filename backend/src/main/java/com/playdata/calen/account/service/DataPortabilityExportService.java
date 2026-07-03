package com.playdata.calen.account.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.familyalbum.domain.FamilyMediaAsset;
import com.playdata.calen.familyalbum.repository.FamilyMediaAssetRepository;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.ledger.service.LedgerEntryService;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DataPortabilityExportService {

    private static final DateTimeFormatter FILE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final AppUserService appUserService;
    private final LedgerEntryService ledgerEntryService;
    private final LedgerAiAnalysisHistoryRepository ledgerAiAnalysisHistoryRepository;
    private final DriveItemRepository driveItemRepository;
    private final TravelMediaAssetRepository travelMediaAssetRepository;
    private final FamilyMediaAssetRepository familyMediaAssetRepository;
    private final UserNotificationService userNotificationService;
    private final ObjectMapper objectMapper;

    @Transactional
    public UserDataArchive exportUserDataArchive(Long userId, LocalDate from, LocalDate to, String secondaryPin) {
        AppUser user = appUserService.getRequiredUser(userId);
        appUserService.ensureSecondaryPinMatches(user, secondaryPin);

        LedgerEntryService.LedgerCsvExport ledgerExport = ledgerEntryService.exportEntriesCsv(userId, from, to);
        List<DriveItem> driveItems = driveItemRepository.findAllByOwner_IdOrderByLastModifiedAtDesc(userId);
        List<TravelMediaAsset> travelMediaAssets = travelMediaAssetRepository.findAllByPlanOwnerIdOrderByUploadedAtDescIdDesc(userId);
        List<FamilyMediaAsset> familyMediaAssets = familyMediaAssetRepository.findAllByOwnerIdOrderByUploadedAtDescIdDesc(userId);
        byte[] driveManifest = buildDriveManifest(driveItems);
        byte[] travelManifest = buildTravelMediaManifest(travelMediaAssets);
        byte[] familyManifest = buildFamilyMediaManifest(familyMediaAssets);
        byte[] metadata = buildMetadata(user, ledgerExport, from, to, driveItems.size(), travelMediaAssets.size(), familyMediaAssets.size());
        String fileName = "travelledger-user-data-" + LocalDate.now(ZoneId.of("Asia/Seoul")).format(FILE_DATE_FORMATTER) + ".zip";
        UserDataArchive archive = new UserDataArchive(
                fileName,
                createPasswordProtectedZip(List.of(
                        new ArchiveEntry("ledger/" + ledgerExport.fileName(), ledgerExport.content()),
                        new ArchiveEntry("metadata/export-metadata.json", metadata),
                        new ArchiveEntry("manifest/drive-items.json", driveManifest),
                        new ArchiveEntry("manifest/travel-media.json", travelManifest),
                        new ArchiveEntry("manifest/family-media.json", familyManifest)
                ), secondaryPin),
                "application/zip"
        );
        notifyPrivacyExportCompleted(userId, from, to);
        return archive;
    }


    private void notifyPrivacyExportCompleted(Long userId, LocalDate from, LocalDate to) {
        try {
            userNotificationService.createSystemNotification(
                    userId,
                    "PRIVACY_EXPORT_DONE",
                    "Data export ready",
                    "Your protected data export archive is ready. Keep the downloaded file and secondary PIN private.",
                    "/profile?privacy=1",
                    privacyExportMetadata(from, to)
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to create privacy export notification: userId={}", userId, exception);
        }
    }

    private String privacyExportMetadata(LocalDate from, LocalDate to) {
        return "{\"status\":\"ready\",\"dateRangeLabel\":\"" + dateRangeLabel(from, to) + "\",\"archiveScope\":\"ledger_csv_and_safe_manifests\"}";
    }

    private String dateRangeLabel(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return "all";
        }
        String fromText = from == null ? "start" : from.toString();
        String toText = to == null ? "today" : to.toString();
        return fromText + ".." + toText;
    }
    private byte[] buildMetadata(
            AppUser user,
            LedgerEntryService.LedgerCsvExport ledgerExport,
            LocalDate from,
            LocalDate to,
            int driveItemCount,
            int travelMediaCount,
            int familyMediaCount
    ) {
        Map<String, Object> metadata = orderedMap();
        metadata.put("exportedAt", Instant.now().toString());
        metadata.put("scope", "ledger_csv_metadata_and_file_manifests");
        metadata.put("owner", Map.of(
                "id", user.getId(),
                "loginId", user.getLoginId(),
                "displayName", user.getDisplayName()
        ));
        metadata.put("range", Map.of(
                "from", from == null ? "" : from.toString(),
                "to", to == null ? "" : to.toString()
        ));
        metadata.put("counts", Map.of(
                "aiAnalysisHistoryCount", ledgerAiAnalysisHistoryRepository.countByOwnerId(user.getId()),
                "driveItemCount", driveItemCount,
                "travelMediaCount", travelMediaCount,
                "familyMediaCount", familyMediaCount
        ));
        metadata.put("files", List.of(
                fileDescriptor("ledger/" + ledgerExport.fileName(), "text/csv", ledgerExport.charset()),
                fileDescriptor("metadata/export-metadata.json", "application/json", StandardCharsets.UTF_8.name()),
                fileDescriptor("manifest/drive-items.json", "application/json", StandardCharsets.UTF_8.name()),
                fileDescriptor("manifest/travel-media.json", "application/json", StandardCharsets.UTF_8.name()),
                fileDescriptor("manifest/family-media.json", "application/json", StandardCharsets.UTF_8.name())
        ));
        metadata.put("safety", Map.of(
                "binaryFilesIncluded", false,
                "storedObjectPathsIncluded", false,
                "publicLinksIncluded", false,
                "rawShareCredentialsIncluded", false
        ));
        return toJsonBytes(metadata, "Failed to create user data export metadata.");
    }

    private byte[] buildDriveManifest(List<DriveItem> items) {
        List<Map<String, Object>> manifestItems = items.stream()
                .map(item -> {
                    Map<String, Object> row = orderedMap();
                    row.put("id", item.getId());
                    row.put("parentId", item.getParent() == null ? null : item.getParent().getId());
                    row.put("itemType", enumName(item.getItemType()));
                    row.put("originalName", safeText(item.getOriginalName()));
                    row.put("extension", safeText(item.getExtension()));
                    row.put("fileSize", item.getFileSize());
                    row.put("locked", item.isLockedFile());
                    row.put("shared", item.isSharedFile());
                    row.put("trashed", item.isTrashed());
                    row.put("uploadedAt", dateTimeText(item.getUploadedAt()));
                    row.put("lastModifiedAt", dateTimeText(item.getLastModifiedAt()));
                    row.put("lastAccessedAt", dateTimeText(item.getLastAccessedAt()));
                    row.put("deletedAt", dateTimeText(item.getDeletedAt()));
                    return row;
                })
                .toList();
        return manifestBytes("drive_items", manifestItems);
    }

    private byte[] buildTravelMediaManifest(List<TravelMediaAsset> assets) {
        List<Map<String, Object>> manifestItems = assets.stream()
                .map(asset -> {
                    Map<String, Object> row = orderedMap();
                    row.put("id", asset.getId());
                    row.put("planId", asset.getPlan() == null ? null : asset.getPlan().getId());
                    row.put("planName", asset.getPlan() == null ? "" : safeText(asset.getPlan().getName()));
                    row.put("recordId", asset.getRecord() == null ? null : asset.getRecord().getId());
                    row.put("recordType", asset.getRecord() == null ? "" : enumName(asset.getRecord().getRecordType()));
                    row.put("mediaType", enumName(asset.getMediaType()));
                    row.put("originalFileName", safeText(asset.getOriginalFileName()));
                    row.put("contentType", safeText(asset.getContentType()));
                    row.put("fileSize", asset.getFileSize());
                    row.put("caption", safeText(asset.getCaption()));
                    row.put("hasGpsMetadata", asset.getGpsLatitude() != null && asset.getGpsLongitude() != null);
                    row.put("gpsExtractedAt", dateTimeText(asset.getGpsExtractedAt()));
                    row.put("uploadedAt", dateTimeText(asset.getUploadedAt()));
                    return row;
                })
                .toList();
        return manifestBytes("travel_media", manifestItems);
    }

    private byte[] buildFamilyMediaManifest(List<FamilyMediaAsset> assets) {
        List<Map<String, Object>> manifestItems = assets.stream()
                .map(asset -> {
                    Map<String, Object> row = orderedMap();
                    row.put("id", asset.getId());
                    row.put("categoryId", asset.getCategory() == null ? null : asset.getCategory().getId());
                    row.put("categoryName", asset.getCategory() == null ? "" : safeText(asset.getCategory().getName()));
                    row.put("mediaType", enumName(asset.getMediaType()));
                    row.put("originalFileName", safeText(asset.getOriginalFileName()));
                    row.put("contentType", safeText(asset.getContentType()));
                    row.put("fileSize", asset.getFileSize());
                    row.put("caption", safeText(asset.getCaption()));
                    row.put("shared", asset.isShared());
                    row.put("capturedAt", dateTimeText(asset.getCapturedAt()));
                    row.put("uploadedAt", dateTimeText(asset.getUploadedAt()));
                    return row;
                })
                .toList();
        return manifestBytes("family_media", manifestItems);
    }

    private byte[] manifestBytes(String type, List<Map<String, Object>> items) {
        Map<String, Object> manifest = orderedMap();
        manifest.put("type", type);
        manifest.put("generatedAt", Instant.now().toString());
        manifest.put("count", items.size());
        manifest.put("items", new ArrayList<>(items));
        manifest.put("omittedSensitiveData", List.of(
                "internal object locator",
                "server file name",
                "public sharing URL",
                "temporary download URL",
                "raw sharing credential"
        ));
        return toJsonBytes(manifest, "Failed to create user data export manifest.");
    }

    private Map<String, Object> fileDescriptor(String path, String contentType, String charset) {
        Map<String, Object> descriptor = orderedMap();
        descriptor.put("path", path);
        descriptor.put("contentType", contentType);
        descriptor.put("charset", charset);
        return descriptor;
    }

    private byte[] toJsonBytes(Object value, String failureMessage) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(failureMessage, exception);
        }
    }

    private Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private String enumName(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    private String dateTimeText(Object value) {
        return value == null ? "" : value.toString();
    }

    private byte[] createPasswordProtectedZip(List<ArchiveEntry> entries, String password) {
        Path tempZipPath = null;
        try {
            tempZipPath = Files.createTempFile("travelledger-user-data-", ".zip");
            try (ZipFile zipFile = new ZipFile(tempZipPath.toFile(), password.toCharArray())) {
                for (ArchiveEntry entry : entries) {
                    ZipParameters zipParameters = new ZipParameters();
                    zipParameters.setFileNameInZip(entry.fileName());
                    zipParameters.setEncryptFiles(true);
                    zipParameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
                    zipParameters.setCompressionMethod(CompressionMethod.DEFLATE);
                    zipParameters.setCompressionLevel(CompressionLevel.NORMAL);
                    try (ByteArrayInputStream inputStream = new ByteArrayInputStream(entry.content())) {
                        zipFile.addStream(inputStream, zipParameters);
                    }
                }
            }
            return Files.readAllBytes(tempZipPath);
        } catch (IOException exception) {
            throw new BadRequestException("Could not create the user data export archive.");
        } finally {
            if (tempZipPath != null) {
                try {
                    Files.deleteIfExists(tempZipPath);
                } catch (IOException ignored) {
                    // Ignore cleanup errors for temporary export files.
                }
            }
        }
    }

    private record ArchiveEntry(String fileName, byte[] content) {
    }

    public record UserDataArchive(String fileName, byte[] content, String contentType) {
    }
}