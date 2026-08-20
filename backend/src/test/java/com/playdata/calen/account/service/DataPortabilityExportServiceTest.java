package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.security.SecondaryPinMismatchException;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.familyalbum.domain.FamilyCategory;
import com.playdata.calen.familyalbum.domain.FamilyMediaAsset;
import com.playdata.calen.familyalbum.domain.FamilyMediaType;
import com.playdata.calen.familyalbum.repository.FamilyMediaAssetRepository;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.ledger.service.LedgerEntryService;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DataPortabilityExportServiceTest {

    private static final Long USER_ID = 7L;
    private static final String SECONDARY_PIN = "12345678";

    @Mock
    private AppUserService appUserService;

    @Mock
    private LedgerEntryService ledgerEntryService;

    @Mock
    private LedgerAiAnalysisHistoryRepository ledgerAiAnalysisHistoryRepository;

    @Mock
    private DriveItemRepository driveItemRepository;

    @Mock
    private TravelMediaAssetRepository travelMediaAssetRepository;

    @Mock
    private FamilyMediaAssetRepository familyMediaAssetRepository;

    @TempDir
    private Path tempDir;

    private ObjectMapper objectMapper;
    private DataPortabilityExportService exportService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        exportService = new DataPortabilityExportService(
                appUserService,
                ledgerEntryService,
                ledgerAiAnalysisHistoryRepository,
                driveItemRepository,
                travelMediaAssetRepository,
                familyMediaAssetRepository,
                objectMapper
        );
    }

    @Test
    void exportUserDataArchiveBuildsEncryptedArchiveWithoutOperationalSecrets() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        AppUser user = user();
        byte[] ledgerCsv = "id,date,memo,amount\n1,2026-01-03,Coffee,4500\n".getBytes(StandardCharsets.UTF_8);
        DriveItem driveItem = driveItem();
        TravelMediaAsset travelMediaAsset = travelMediaAsset(user);
        FamilyMediaAsset familyMediaAsset = familyMediaAsset(user);

        when(appUserService.getRequiredUser(USER_ID)).thenReturn(user);
        when(ledgerEntryService.exportEntriesCsv(USER_ID, from, to))
                .thenReturn(new LedgerEntryService.LedgerCsvExport(
                        "ledger-2026.csv",
                        ledgerCsv,
                        StandardCharsets.UTF_8.name()
                ));
        when(ledgerAiAnalysisHistoryRepository.countByOwnerId(USER_ID)).thenReturn(3L);
        when(driveItemRepository.findAllByOwner_IdOrderByLastModifiedAtDesc(USER_ID)).thenReturn(List.of(driveItem));
        when(travelMediaAssetRepository.findAllByPlanOwnerIdOrderByUploadedAtDescIdDesc(USER_ID)).thenReturn(List.of(travelMediaAsset));
        when(familyMediaAssetRepository.findAllByOwnerIdOrderByUploadedAtDescIdDesc(USER_ID)).thenReturn(List.of(familyMediaAsset));

        DataPortabilityExportService.UserDataArchive archive =
                exportService.exportUserDataArchive(USER_ID, from, to, SECONDARY_PIN);

        assertThat(archive.fileName())
                .startsWith("travelledger-user-data-")
                .endsWith(".zip");
        assertThat(archive.contentType()).isEqualTo("application/zip");
        assertThat(archive.content()).isNotEmpty();

        Path zipPath = tempDir.resolve(archive.fileName());
        Files.write(zipPath, archive.content());
        try (ZipFile zipFile = new ZipFile(zipPath.toFile(), SECONDARY_PIN.toCharArray())) {
            assertThat(zipFile.isValidZipFile()).isTrue();
            assertThat(zipFile.isEncrypted()).isTrue();

            FileHeader ledgerHeader = zipFile.getFileHeader("ledger/ledger-2026.csv");
            FileHeader metadataHeader = zipFile.getFileHeader("metadata/export-metadata.json");
            FileHeader driveManifestHeader = zipFile.getFileHeader("manifest/drive-items.json");
            FileHeader travelManifestHeader = zipFile.getFileHeader("manifest/travel-media.json");
            FileHeader familyManifestHeader = zipFile.getFileHeader("manifest/family-media.json");
            assertThat(ledgerHeader).isNotNull();
            assertThat(metadataHeader).isNotNull();
            assertThat(driveManifestHeader).isNotNull();
            assertThat(travelManifestHeader).isNotNull();
            assertThat(familyManifestHeader).isNotNull();

            assertThat(readZipEntry(zipFile, ledgerHeader)).isEqualTo(ledgerCsv);

            String metadataJson = new String(readZipEntry(zipFile, metadataHeader), StandardCharsets.UTF_8);
            JsonNode metadata = objectMapper.readTree(metadataJson);
            assertThat(metadata.path("scope").asText()).isEqualTo("ledger_csv_metadata_and_file_manifests");
            assertThat(metadata.path("owner").path("id").asLong()).isEqualTo(USER_ID);
            assertThat(metadata.path("owner").path("loginId").asText()).isEqualTo("owner@example.com");
            assertThat(metadata.path("owner").path("displayName").asText()).isEqualTo("Owner");
            assertThat(metadata.path("range").path("from").asText()).isEqualTo("2026-01-01");
            assertThat(metadata.path("range").path("to").asText()).isEqualTo("2026-01-31");
            assertThat(metadata.path("counts").path("aiAnalysisHistoryCount").asLong()).isEqualTo(3L);
            assertThat(metadata.path("counts").path("driveItemCount").asInt()).isEqualTo(1);
            assertThat(metadata.path("counts").path("travelMediaCount").asInt()).isEqualTo(1);
            assertThat(metadata.path("counts").path("familyMediaCount").asInt()).isEqualTo(1);
            assertThat(metadataJson)
                    .contains("ledger/ledger-2026.csv")
                    .contains("manifest/drive-items.json")
                    .contains("manifest/travel-media.json")
                    .contains("manifest/family-media.json");

            JsonNode driveManifest = objectMapper.readTree(readZipEntry(zipFile, driveManifestHeader));
            JsonNode travelManifest = objectMapper.readTree(readZipEntry(zipFile, travelManifestHeader));
            JsonNode familyManifest = objectMapper.readTree(readZipEntry(zipFile, familyManifestHeader));
            assertThat(driveManifest.path("count").asInt()).isEqualTo(1);
            assertThat(driveManifest.path("items").get(0).path("originalName").asText()).isEqualTo("budget.xlsx");
            assertThat(travelManifest.path("count").asInt()).isEqualTo(1);
            assertThat(travelManifest.path("items").get(0).path("planName").asText()).isEqualTo("Osaka");
            assertThat(travelManifest.path("items").get(0).path("hasGpsMetadata").asBoolean()).isTrue();
            assertThat(familyManifest.path("count").asInt()).isEqualTo(1);
            assertThat(familyManifest.path("items").get(0).path("categoryName").asText()).isEqualTo("Family");

            String normalizedArchiveText = (metadataJson
                    + new String(readZipEntry(zipFile, driveManifestHeader), StandardCharsets.UTF_8)
                    + new String(readZipEntry(zipFile, travelManifestHeader), StandardCharsets.UTF_8)
                    + new String(readZipEntry(zipFile, familyManifestHeader), StandardCharsets.UTF_8))
                    .toLowerCase(Locale.ROOT);
            assertThat(normalizedArchiveText)
                    .doesNotContain("apikey")
                    .doesNotContain("api_key")
                    .doesNotContain("signedurl")
                    .doesNotContain("signed_url")
                    .doesNotContain("secret")
                    .doesNotContain("password")
                    .doesNotContain("token")
                    .doesNotContain("workflowurl")
                    .doesNotContain("storagepath")
                    .doesNotContain("storedname");
        }

        verify(appUserService).ensureSecondaryPinMatches(user, SECONDARY_PIN);
        verify(ledgerEntryService).exportEntriesCsv(USER_ID, from, to);
        verify(ledgerAiAnalysisHistoryRepository).countByOwnerId(USER_ID);
        verify(driveItemRepository).findAllByOwner_IdOrderByLastModifiedAtDesc(USER_ID);
        verify(travelMediaAssetRepository).findAllByPlanOwnerIdOrderByUploadedAtDescIdDesc(USER_ID);
        verify(familyMediaAssetRepository).findAllByOwnerIdOrderByUploadedAtDescIdDesc(USER_ID);
    }

    @Test
    void exportUserDataArchiveVerifiesSecondaryPinBeforeExportingLedgerData() {
        AppUser user = user();
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(user);
        doThrow(new SecondaryPinMismatchException())
                .when(appUserService)
                .ensureSecondaryPinMatches(user, "00000000");

        assertThatThrownBy(() -> exportService.exportUserDataArchive(
                USER_ID,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                "00000000"
        )).isInstanceOf(SecondaryPinMismatchException.class);

        verifyNoInteractions(
                ledgerEntryService,
                ledgerAiAnalysisHistoryRepository,
                driveItemRepository,
                travelMediaAssetRepository,
                familyMediaAssetRepository
        );
    }

    private static AppUser user() {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        user.setLoginId("owner@example.com");
        user.setDisplayName("Owner");
        user.setPasswordHash("{noop}password");
        user.setSecondaryPinHash("{noop}" + SECONDARY_PIN);
        user.setActive(true);
        return user;
    }

    private static DriveItem driveItem() {
        DriveItem item = new DriveItem();
        item.setId(11L);
        item.setItemType(DriveItemType.FILE);
        item.setOriginalName("budget.xlsx");
        item.setExtension("xlsx");
        item.setStoredName("internal-object-name");
        item.setStoragePath("drive/private/internal-object-name");
        item.setFileSize(2048L);
        item.setLockedFile(true);
        item.setSharedFile(false);
        item.setTrashed(false);
        item.setUploadedAt(LocalDateTime.of(2026, 1, 3, 10, 0));
        item.setLastModifiedAt(LocalDateTime.of(2026, 1, 4, 10, 0));
        return item;
    }

    private static TravelMediaAsset travelMediaAsset(AppUser owner) {
        TravelPlan plan = new TravelPlan();
        plan.setId(21L);
        plan.setOwner(owner);
        plan.setName("Osaka");
        plan.setStartDate(LocalDate.of(2026, 1, 10));
        plan.setEndDate(LocalDate.of(2026, 1, 15));

        TravelExpenseRecord record = new TravelExpenseRecord();
        record.setId(22L);
        record.setPlan(plan);
        record.setRecordType(TravelRecordType.MEMORY);
        record.setExpenseDate(LocalDate.of(2026, 1, 11));
        record.setCategory("Memory");
        record.setTitle("Castle");
        record.setAmount(BigDecimal.ZERO);

        TravelMediaAsset asset = new TravelMediaAsset();
        asset.setId(23L);
        asset.setPlan(plan);
        asset.setRecord(record);
        asset.setUploadedBy(owner);
        asset.setMediaType(TravelMediaType.PHOTO);
        asset.setOriginalFileName("castle.jpg");
        asset.setStoredFileName("internal-castle.jpg");
        asset.setStoragePath("travel/internal-castle.jpg");
        asset.setContentType("image/jpeg");
        asset.setFileSize(4096L);
        asset.setCaption("Castle photo");
        asset.setGpsLatitude(new BigDecimal("34.6873000"));
        asset.setGpsLongitude(new BigDecimal("135.5262000"));
        asset.setGpsExtractedAt(LocalDateTime.of(2026, 1, 11, 8, 0));
        asset.setUploadedAt(LocalDateTime.of(2026, 1, 11, 9, 0));
        return asset;
    }

    private static FamilyMediaAsset familyMediaAsset(AppUser owner) {
        FamilyCategory category = new FamilyCategory();
        category.setId(31L);
        category.setOwner(owner);
        category.setName("Family");

        FamilyMediaAsset asset = new FamilyMediaAsset();
        asset.setId(32L);
        asset.setCategory(category);
        asset.setOwner(owner);
        asset.setMediaType(FamilyMediaType.PHOTO);
        asset.setOriginalFileName("picnic.jpg");
        asset.setStoredFileName("internal-picnic.jpg");
        asset.setStoragePath("family/internal-picnic.jpg");
        asset.setContentType("image/jpeg");
        asset.setFileSize(8192L);
        asset.setCaption("Picnic");
        asset.setShared(true);
        asset.setCapturedAt(LocalDateTime.of(2026, 1, 5, 12, 0));
        asset.setUploadedAt(LocalDateTime.of(2026, 1, 5, 13, 0));
        return asset;
    }

    private static byte[] readZipEntry(ZipFile zipFile, FileHeader fileHeader) throws IOException {
        return zipFile.getInputStream(fileHeader).readAllBytes();
    }
}