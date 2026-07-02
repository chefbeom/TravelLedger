package com.playdata.calen.ledger.ocr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.UserNotificationService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisProperties;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus;
import com.playdata.calen.ledger.dto.LedgerImageAnalysisHistoryResponse;
import com.playdata.calen.ledger.dto.LedgerOcrAnalyzeResponse;
import com.playdata.calen.ledger.dto.LedgerOcrEntrySuggestionResponse;
import com.playdata.calen.ledger.dto.LedgerOcrLineItemResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteAnalyzeResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteLineItem;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteParsedResult;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerOcrService {

    private static final int MAX_TEXT_LENGTH = 500;
    private static final List<String> ALLOWED_IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp", ".bmp");
    private static final byte[] PNG_SIGNATURE = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final AppUserService appUserService;
    private final LedgerOcrProperties properties;
    private final LedgerAiAnalysisProperties aiProperties;
    private final LedgerOcrRemoteClient remoteClient;
    private final LedgerImageAnalysisRequestRepository imageAnalysisRequestRepository;
    private final ObjectMapper objectMapper;
    private final UserNotificationService userNotificationService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public LedgerOcrAnalyzeResponse analyze(Long userId, MultipartFile file, String documentType) {
        AppUser owner = appUserService.getRequiredUser(userId);
        Timer.Sample ocrRequestTimer = startOcrRequestTimer();
        LedgerImageAnalysisRequest history = null;

        try {
            validateReady();
            validateFile(file);

            String normalizedDocumentType = normalizeDocumentType(documentType);
            history = createImageAnalysisRequest(owner, file, normalizedDocumentType);
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

            LedgerOcrAnalyzeResponse response = new LedgerOcrAnalyzeResponse(
                    history.getId(),
                    LedgerImageAnalysisStatus.COMPLETED.name(),
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
            completeImageAnalysisRequest(history, response);
            recordOcrRequest(ocrRequestTimer, "success", "none");
            return response;
        } catch (RuntimeException exception) {
            if (history != null) {
                failImageAnalysisRequest(history, exception);
            }
            String failureReason = ocrFailureReason(exception);
            recordOcrRequest(ocrRequestTimer, "failure", failureReason);
            notifyOcrFailure(userId, failureReason);
            throw exception;
        }
    }

    public Page<LedgerImageAnalysisHistoryResponse> listHistories(Long userId, Pageable pageable) {
        appUserService.getRequiredUser(userId);
        return imageAnalysisRequestRepository.findAllByOwnerIdOrderByCreatedAtDescIdDesc(userId, pageable)
                .map(this::toHistoryResponse);
    }

    public LedgerImageAnalysisHistoryResponse getHistory(Long userId, Long historyId) {
        appUserService.getRequiredUser(userId);
        return imageAnalysisRequestRepository.findByIdAndOwnerId(historyId, userId)
                .map(this::toHistoryResponse)
                .orElseThrow(() -> new BadRequestException("Image analysis request history was not found."));
    }

    public LedgerImageAnalysisHistoryResponse cancelHistory(Long userId, Long historyId) {
        appUserService.getRequiredUser(userId);
        LedgerImageAnalysisRequest history = imageAnalysisRequestRepository.findByIdAndOwnerId(historyId, userId)
                .orElseThrow(() -> new BadRequestException("Image analysis request history was not found."));
        if (history.getStatus() != LedgerImageAnalysisStatus.CANCELLED) {
            history.setStatus(LedgerImageAnalysisStatus.CANCELLED);
            history.setCancelledAt(LocalDateTime.now());
            history.setSummary("사용자가 이미지 분석 요청을 취소했습니다.");
            imageAnalysisRequestRepository.save(history);
        }
        return toHistoryResponse(history);
    }

    private LedgerImageAnalysisRequest createImageAnalysisRequest(AppUser owner, MultipartFile file, String documentType) {
        LedgerImageAnalysisRequest history = new LedgerImageAnalysisRequest();
        history.setOwner(owner);
        history.setStatus(LedgerImageAnalysisStatus.PROCESSING);
        history.setDocumentType(documentType);
        history.setFileName(limit(file.getOriginalFilename(), 260));
        history.setContentType(limit(file.getContentType(), 120));
        history.setFileSizeBytes(file.getSize());
        history.setSummary("AI 이미지 분석 요청을 처리 중입니다.");
        return imageAnalysisRequestRepository.save(history);
    }

    private void completeImageAnalysisRequest(LedgerImageAnalysisRequest history, LedgerOcrAnalyzeResponse response) {
        history.setStatus(LedgerImageAnalysisStatus.COMPLETED);
        history.setCompletedAt(LocalDateTime.now());
        history.setDocumentType(firstNonBlank(response.documentType(), history.getDocumentType()));
        history.setRawText(response.rawText());
        history.setSummary(limit(summarizeResponse(response), 500));
        history.setResultJson(writeResponseJson(response));
        imageAnalysisRequestRepository.save(history);
    }

    private void failImageAnalysisRequest(LedgerImageAnalysisRequest history, RuntimeException exception) {
        history.setStatus(LedgerImageAnalysisStatus.FAILED);
        history.setCompletedAt(LocalDateTime.now());
        history.setErrorMessage(limit(exception.getMessage(), 1000));
        history.setSummary("AI 이미지 분석 요청에 실패했습니다.");
        imageAnalysisRequestRepository.save(history);
    }

    private LedgerImageAnalysisHistoryResponse toHistoryResponse(LedgerImageAnalysisRequest history) {
        return new LedgerImageAnalysisHistoryResponse(
                history.getId(),
                history.getStatus() == null ? null : history.getStatus().name(),
                history.getDocumentType(),
                history.getFileName(),
                history.getContentType(),
                history.getFileSizeBytes(),
                history.getSummary(),
                history.getErrorMessage(),
                history.getRawText(),
                readResponseJson(history.getResultJson()),
                history.getCreatedAt(),
                history.getUpdatedAt(),
                history.getCompletedAt(),
                history.getCancelledAt()
        );
    }

    private String summarizeResponse(LedgerOcrAnalyzeResponse response) {
        LedgerOcrEntrySuggestionResponse entry = response.suggestedEntry();
        int count = response.suggestedEntries() == null ? 0 : response.suggestedEntries().size();
        if (entry == null) {
            return count > 0 ? count + "건 거래 후보 추출" : "추출된 거래 후보가 없습니다.";
        }
        String amount = entry.amount() == null ? "금액 확인 필요" : entry.amount().toPlainString() + " KRW";
        String title = firstNonBlank(entry.title(), "제목 확인 필요");
        return title + " - " + amount + " - " + Math.max(count, 1) + "? ??";
    }

    private String writeResponseJson(LedgerOcrAnalyzeResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialize ledger image analysis response: analysisId={}", response.analysisId(), exception);
            return "";
        }
    }

    private LedgerOcrAnalyzeResponse readResponseJson(String json) {
        if (!isPresent(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, LedgerOcrAnalyzeResponse.class);
        } catch (JsonProcessingException exception) {
            log.warn("Failed to deserialize ledger image analysis response history", exception);
            return null;
        }
    }
    private void notifyOcrFailure(Long userId, String failureReason) {
        if ("invalid_file".equals(failureReason)) {
            return;
        }
        try {
            userNotificationService.createSystemNotification(
                    userId,
                    "AI_OR_OCR_FAILED",
                    "AI image analysis failed",
                    "Receipt image analysis could not be completed. Please check the AI server or try again later.",
                    "/calendar?receiptOcr=1",
                    "{\"reason\":\"" + failureReason + "\"}"
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to create ledger OCR notification: userId={}, reason={}", userId, failureReason, exception);
        }
    }

    private Timer.Sample startOcrRequestTimer() {
        return meterRegistry == null ? null : Timer.start(meterRegistry);
    }

    private void recordOcrRequest(Timer.Sample sample, String status, String reason) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("calen.ledger.ocr.requests")
                .description("Ledger OCR analysis requests")
                .tag("status", status)
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
        if (sample != null) {
            sample.stop(Timer.builder("calen.ledger.ocr.request")
                    .description("Ledger OCR analysis request duration")
                    .tag("status", status)
                    .tag("reason", reason)
                    .register(meterRegistry));
        }
    }

    private String ocrFailureReason(RuntimeException exception) {
        if (exception instanceof BadRequestException) {
            String message = exception.getMessage();
            if ("AI image analysis is not enabled or is missing server configuration.".equals(message)) {
                return "not_configured";
            }
            if ("Upload a receipt image first.".equals(message)
                    || "Receipt image exceeds the AI image analysis upload size limit.".equals(message)
                    || "Only image files can be analyzed.".equals(message)) {
                return "invalid_file";
            }
            return "bad_request";
        }
        return "runtime_error";
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
        if (!aiProperties.isConfigured() || !aiProperties.isLmStudioConfigured()) {
            throw new BadRequestException("AI image analysis is not enabled or is missing server configuration.");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Upload a receipt image first.");
        }
        if (file.getSize() > properties.getMaxFileSize().toBytes()) {
            throw new BadRequestException("Receipt image exceeds the AI image analysis upload size limit.");
        }

        String contentType = normalizeContentType(file.getContentType());
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String extension = resolveImageExtension(fileName);
        if (extension.isBlank()
                || !isAllowedImageContentType(extension, contentType)
                || !hasAllowedImageSignature(extension, file)) {
            throw new BadRequestException("Only image files can be analyzed.");
        }
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        return contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }

    private String resolveImageExtension(String fileName) {
        return ALLOWED_IMAGE_EXTENSIONS.stream()
                .filter(fileName::endsWith)
                .findFirst()
                .orElse("");
    }

    private boolean isAllowedImageContentType(String extension, String contentType) {
        return switch (extension) {
            case ".jpg", ".jpeg" -> contentType.equals("image/jpeg")
                    || contentType.equals("image/jpg")
                    || contentType.equals("image/pjpeg");
            case ".png" -> contentType.equals("image/png");
            case ".webp" -> contentType.equals("image/webp");
            case ".bmp" -> contentType.equals("image/bmp") || contentType.equals("image/x-ms-bmp");
            default -> false;
        };
    }

    private boolean hasAllowedImageSignature(String extension, MultipartFile file) {
        byte[] header = readImageHeader(file);
        return switch (extension) {
            case ".jpg", ".jpeg" -> header.length >= 3
                    && (header[0] & 0xFF) == 0xFF
                    && (header[1] & 0xFF) == 0xD8
                    && (header[2] & 0xFF) == 0xFF;
            case ".png" -> startsWith(header, PNG_SIGNATURE);
            case ".webp" -> header.length >= 12
                    && header[0] == 'R'
                    && header[1] == 'I'
                    && header[2] == 'F'
                    && header[3] == 'F'
                    && header[8] == 'W'
                    && header[9] == 'E'
                    && header[10] == 'B'
                    && header[11] == 'P';
            case ".bmp" -> header.length >= 2 && header[0] == 'B' && header[1] == 'M';
            default -> false;
        };
    }

    private byte[] readImageHeader(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return inputStream.readNBytes(12);
        } catch (IOException exception) {
            throw new BadRequestException("Only image files can be analyzed.");
        }
    }

    private boolean startsWith(byte[] header, byte[] signature) {
        if (header.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (header[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
    private LedgerOcrEntrySuggestionResponse buildSuggestion(
            RemoteParsedResult parsed,
            EntryType entryType,
            List<LedgerOcrLineItemResponse> lineItems
    ) {
        LocalDate entryDate = parsed != null && parsed.entryDate() != null ? parsed.entryDate() : LocalDate.now();
        LocalTime entryTime = parsed != null && parsed.entryTime() != null ? parsed.entryTime() : LocalTime.MIDNIGHT;
        BigDecimal amount = parsed != null
                && parsed.amount() != null
                && parsed.amount().abs().compareTo(BigDecimal.ZERO) > 0
                ? parsed.amount().abs()
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