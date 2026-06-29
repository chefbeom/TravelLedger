package com.playdata.calen.account.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.ledger.service.LedgerEntryService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DataPortabilityExportService {

    private static final DateTimeFormatter FILE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final AppUserService appUserService;
    private final LedgerEntryService ledgerEntryService;
    private final LedgerAiAnalysisHistoryRepository ledgerAiAnalysisHistoryRepository;
    private final ObjectMapper objectMapper;

    public UserDataArchive exportUserDataArchive(Long userId, LocalDate from, LocalDate to, String secondaryPin) {
        AppUser user = appUserService.getRequiredUser(userId);
        appUserService.ensureSecondaryPinMatches(user, secondaryPin);

        LedgerEntryService.LedgerCsvExport ledgerExport = ledgerEntryService.exportEntriesCsv(userId, from, to);
        byte[] metadata = buildMetadata(user, ledgerExport, from, to);
        String fileName = "travelledger-user-data-" + LocalDate.now(ZoneId.of("Asia/Seoul")).format(FILE_DATE_FORMATTER) + ".zip";
        return new UserDataArchive(
                fileName,
                createPasswordProtectedZip(List.of(
                        new ArchiveEntry("ledger/" + ledgerExport.fileName(), ledgerExport.content()),
                        new ArchiveEntry("metadata/export-metadata.json", metadata)
                ), secondaryPin),
                "application/zip"
        );
    }

    private byte[] buildMetadata(
            AppUser user,
            LedgerEntryService.LedgerCsvExport ledgerExport,
            LocalDate from,
            LocalDate to
    ) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                    "exportedAt", Instant.now().toString(),
                    "scope", "ledger_csv_and_metadata",
                    "owner", Map.of(
                            "id", user.getId(),
                            "loginId", user.getLoginId(),
                            "displayName", user.getDisplayName()
                    ),
                    "range", Map.of(
                            "from", from == null ? "" : from.toString(),
                            "to", to == null ? "" : to.toString()
                    ),
                    "counts", Map.of(
                            "aiAnalysisHistoryCount", ledgerAiAnalysisHistoryRepository.countByOwnerId(user.getId())
                    ),
                    "files", List.of(
                            Map.of(
                                    "path", "ledger/" + ledgerExport.fileName(),
                                    "contentType", "text/csv",
                                    "charset", ledgerExport.charset()
                            ),
                            Map.of(
                                    "path", "metadata/export-metadata.json",
                                    "contentType", "application/json",
                                    "charset", StandardCharsets.UTF_8.name()
                            )
                    )
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to create user data export metadata.", exception);
        }
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