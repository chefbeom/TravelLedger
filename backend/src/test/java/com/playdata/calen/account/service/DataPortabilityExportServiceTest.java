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
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.ledger.service.LedgerEntryService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
                objectMapper
        );
    }

    @Test
    void exportUserDataArchiveBuildsEncryptedArchiveWithoutOperationalSecrets() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        AppUser user = user();
        byte[] ledgerCsv = "id,date,memo,amount\n1,2026-01-03,Coffee,4500\n".getBytes(StandardCharsets.UTF_8);

        when(appUserService.getRequiredUser(USER_ID)).thenReturn(user);
        when(ledgerEntryService.exportEntriesCsv(USER_ID, from, to))
                .thenReturn(new LedgerEntryService.LedgerCsvExport(
                        "ledger-2026.csv",
                        ledgerCsv,
                        StandardCharsets.UTF_8.name()
                ));
        when(ledgerAiAnalysisHistoryRepository.countByOwnerId(USER_ID)).thenReturn(3L);

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
            assertThat(ledgerHeader).isNotNull();
            assertThat(metadataHeader).isNotNull();

            assertThat(readZipEntry(zipFile, ledgerHeader)).isEqualTo(ledgerCsv);

            String metadataJson = new String(readZipEntry(zipFile, metadataHeader), StandardCharsets.UTF_8);
            JsonNode metadata = objectMapper.readTree(metadataJson);
            assertThat(metadata.path("scope").asText()).isEqualTo("ledger_csv_and_metadata");
            assertThat(metadata.path("owner").path("id").asLong()).isEqualTo(USER_ID);
            assertThat(metadata.path("owner").path("loginId").asText()).isEqualTo("owner@example.com");
            assertThat(metadata.path("owner").path("displayName").asText()).isEqualTo("Owner");
            assertThat(metadata.path("range").path("from").asText()).isEqualTo("2026-01-01");
            assertThat(metadata.path("range").path("to").asText()).isEqualTo("2026-01-31");
            assertThat(metadata.path("counts").path("aiAnalysisHistoryCount").asLong()).isEqualTo(3L);
            assertThat(metadataJson).contains("ledger/ledger-2026.csv");

            String normalizedMetadata = metadataJson.toLowerCase(Locale.ROOT);
            assertThat(normalizedMetadata)
                    .doesNotContain("apikey")
                    .doesNotContain("api_key")
                    .doesNotContain("signedurl")
                    .doesNotContain("signed_url")
                    .doesNotContain("secret")
                    .doesNotContain("password")
                    .doesNotContain("token")
                    .doesNotContain("workflowurl");
        }

        verify(appUserService).ensureSecondaryPinMatches(user, SECONDARY_PIN);
        verify(ledgerEntryService).exportEntriesCsv(USER_ID, from, to);
        verify(ledgerAiAnalysisHistoryRepository).countByOwnerId(USER_ID);
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

        verifyNoInteractions(ledgerEntryService, ledgerAiAnalysisHistoryRepository);
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

    private static byte[] readZipEntry(ZipFile zipFile, FileHeader fileHeader) throws IOException {
        return zipFile.getInputStream(fileHeader).readAllBytes();
    }
}