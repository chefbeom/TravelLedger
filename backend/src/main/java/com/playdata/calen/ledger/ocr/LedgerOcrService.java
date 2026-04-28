package com.playdata.calen.ledger.ocr;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.dto.LedgerOcrAnalyzeResponse;
import com.playdata.calen.ledger.dto.LedgerOcrEntrySuggestionResponse;
import com.playdata.calen.ledger.dto.LedgerOcrLineItemResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteAnalyzeResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteLineItem;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteParsedResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class LedgerOcrService {

    private static final int MAX_TEXT_LENGTH = 500;
    private static final List<String> ALLOWED_IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp", ".bmp");

    private final AppUserService appUserService;
    private final LedgerOcrProperties properties;
    private final LedgerOcrRemoteClient remoteClient;

    public LedgerOcrAnalyzeResponse analyze(Long userId, MultipartFile file, String documentType) {
        appUserService.getRequiredUser(userId);
        validateReady();
        validateFile(file);

        String normalizedDocumentType = normalizeDocumentType(documentType);
        RemoteAnalyzeResponse remoteResponse = remoteClient.analyze(file, normalizedDocumentType);
        List<RemoteParsedResult> parsedEntries = resolveParsedEntries(remoteResponse);
        RemoteParsedResult parsed = parsedEntries.isEmpty() ? remoteResponse.parsed() : parsedEntries.get(0);
        EntryType entryType = parsed != null && parsed.entryType() == EntryType.INCOME
                ? EntryType.INCOME
                : EntryType.EXPENSE;

        List<LedgerOcrLineItemResponse> lineItems = mapLineItems(parsed);
        LedgerOcrEntrySuggestionResponse suggestion = buildSuggestion(parsed, entryType, lineItems);
        List<LedgerOcrEntrySuggestionResponse> suggestions = parsedEntries.stream()
                .map(this::buildSuggestionForParsedEntry)
                .toList();
        if (suggestions.isEmpty() && suggestion != null) {
            suggestions = List.of(suggestion);
        }

        return new LedgerOcrAnalyzeResponse(
                firstNonBlank(remoteResponse.documentType(), normalizedDocumentType),
                remoteResponse.rawText() == null ? "" : remoteResponse.rawText(),
                suggestion,
                suggestions,
                lineItems,
                parsed != null ? parsed.confidence() : null,
                parsed != null && parsed.warnings() != null ? parsed.warnings() : List.of(),
                parsed != null ? limit(parsed.vendor(), MAX_TEXT_LENGTH) : null,
                null,
                null,
                remoteResponse.timing() == null ? java.util.Map.of() : remoteResponse.timing()
        );
    }

    private String normalizeDocumentType(String documentType) {
        if (documentType == null || documentType.isBlank()) {
            return "AUTO";
        }
        String normalized = documentType.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "RECEIPT", "PAYMENT_CAPTURE", "AUTO" -> normalized;
            default -> "AUTO";
        };
    }

    private List<RemoteParsedResult> resolveParsedEntries(RemoteAnalyzeResponse remoteResponse) {
        if (remoteResponse.parsedEntries() != null && !remoteResponse.parsedEntries().isEmpty()) {
            return remoteResponse.parsedEntries().stream()
                    .filter(Objects::nonNull)
                    .toList();
        }
        if (remoteResponse.parsed() != null) {
            return List.of(remoteResponse.parsed());
        }
        return List.of();
    }

    private LedgerOcrEntrySuggestionResponse buildSuggestionForParsedEntry(RemoteParsedResult parsed) {
        EntryType entryType = parsed != null && parsed.entryType() == EntryType.INCOME
                ? EntryType.INCOME
                : EntryType.EXPENSE;
        return buildSuggestion(parsed, entryType, mapLineItems(parsed));
    }

    private void validateReady() {
        if (!properties.isConfigured()) {
            throw new BadRequestException("OCR analysis is not enabled or is missing server configuration.");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Upload a receipt image first.");
        }
        if (file.getSize() > properties.getMaxFileSize().toBytes()) {
            throw new BadRequestException("Receipt image exceeds the OCR upload size limit.");
        }

        String contentType = file.getContentType();
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        boolean imageContentType = contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
        boolean imageExtension = ALLOWED_IMAGE_EXTENSIONS.stream().anyMatch(fileName::endsWith);
        if (!imageContentType && !imageExtension) {
            throw new BadRequestException("Only image files can be analyzed.");
        }
    }

    private LedgerOcrEntrySuggestionResponse buildSuggestion(
            RemoteParsedResult parsed,
            EntryType entryType,
            List<LedgerOcrLineItemResponse> lineItems
    ) {
        LocalDate entryDate = parsed != null && parsed.entryDate() != null ? parsed.entryDate() : LocalDate.now();
        LocalTime entryTime = parsed != null && parsed.entryTime() != null ? parsed.entryTime() : LocalTime.MIDNIGHT;
        BigDecimal amount = parsed != null && parsed.amount() != null && parsed.amount().compareTo(BigDecimal.ZERO) > 0
                ? parsed.amount()
                : null;
        String title = limit(firstNonBlank(
                parsed != null ? parsed.title() : null,
                parsed != null ? parsed.vendor() : null,
                lineItems.isEmpty() ? null : lineItems.get(0).itemName(),
                "Receipt"
        ), 120);
        String memo = limit(buildMemo(parsed, lineItems), MAX_TEXT_LENGTH);

        return new LedgerOcrEntrySuggestionResponse(
                entryDate,
                entryTime,
                title,
                memo,
                amount,
                entryType,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private String buildMemo(RemoteParsedResult parsed, List<LedgerOcrLineItemResponse> lineItems) {
        if (parsed == null) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        if (!lineItems.isEmpty()) {
            String itemSummary = lineItems.stream()
                    .limit(5)
                    .map(LedgerOcrLineItemResponse::itemName)
                    .filter(this::isPresent)
                    .toList()
                    .stream()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            addIfPresent(parts, itemSummary);
        }
        addIfPresent(parts, parsed.memo());
        return parts.isEmpty() ? null : String.join(" / ", parts);
    }

    private void addIfPresent(List<String> parts, String value) {
        if (isPresent(value)) {
            parts.add(value.trim());
        }
    }

    private List<LedgerOcrLineItemResponse> mapLineItems(RemoteParsedResult parsed) {
        if (parsed == null || parsed.lineItems() == null) {
            return List.of();
        }
        return parsed.lineItems().stream()
                .filter(Objects::nonNull)
                .map(this::mapLineItem)
                .toList();
    }

    private LedgerOcrLineItemResponse mapLineItem(RemoteLineItem item) {
        return new LedgerOcrLineItemResponse(
                limit(item.itemName(), 160),
                item.quantity(),
                limit(item.unit(), 30),
                item.price()
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (isPresent(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

}
