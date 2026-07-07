package com.playdata.calen.ledger.ocr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.UserNotificationService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisProperties;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus;
import com.playdata.calen.ledger.dto.LedgerImageAnalysisHistoryResponse;
import com.playdata.calen.ledger.dto.LedgerImageAnalysisHistoryDeleteResponse;
import com.playdata.calen.ledger.dto.LedgerOcrAnalyzeResponse;
import com.playdata.calen.ledger.dto.LedgerOcrEntrySuggestionResponse;
import com.playdata.calen.ledger.dto.LedgerOcrLineItemResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteAnalyzeResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteLineItem;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteParsedResult;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository.ExistingEntryStyleAggregate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerOcrService {

    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_USER_PROMPT_LENGTH = 2400;
    private static final int EXISTING_ENTRY_STYLE_EXAMPLE_LIMIT = 12;
    private static final int MAX_STYLE_FIELD_LENGTH = 90;
    private static final int MAX_STYLE_MEMO_LENGTH = 140;
    private static final int MAX_CATEGORY_CRITERIA_GROUPS = 80;
    private static final int MAX_CATEGORY_CRITERIA_DETAILS_PER_GROUP = 12;
    private static final int MAX_CATEGORY_CRITERIA_NAME_LENGTH = 40;
    private static final String UNCATEGORIZED_CATEGORY_NAME = "\uBBF8\uBD84\uB958";
    private static final List<String> ALLOWED_IMAGE_EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp", ".bmp");
    private static final byte[] PNG_SIGNATURE = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final List<Pattern> FINAL_TOTAL_AMOUNT_PATTERNS = List.of(
            Pattern.compile("(?i)(?:총\\s*결제\\s*금액|총\\s*금액|합계|승인\\s*금액|TOTAL|GRAND\\s*TOTAL)[^0-9]{0,40}([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,9})\\s*(?:원|KRW)?"),
            Pattern.compile("(?i)(?:paid\\s*amount|approved\\s*amount|total\\s*amount)[^0-9]{0,40}([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,9})\\s*(?:원|KRW)?")
    );
    private static final List<Pattern> ORDER_ROW_AMOUNT_PATTERNS = List.of(
            Pattern.compile("(?i)(?:상품\\s*금액(?:\\s*\\([^)]*\\))?|주문\\s*금액(?:\\s*\\([^)]*\\))?)[^0-9]{0,40}([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,9})\\s*(?:원|KRW)?"),
            Pattern.compile("(?i)(?:product\\s*amount|order\\s*amount|item\\s*amount)[^0-9]{0,40}([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,9})\\s*(?:원|KRW)?")
    );
    private static final List<Pattern> PAYMENT_AMOUNT_PATTERNS = List.of(
            Pattern.compile("(?i)(?:결제\\s*금액|결제액)(?:(?!TAXABLE|VAT|과세|부가세)[^0-9]){0,40}([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,9})\\s*(?:원|KRW)?"),
            Pattern.compile("(?i)(?:payment\\s*amount|paid\\s*price)[^0-9]{0,40}([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,9})\\s*(?:원|KRW)?")
    );
    private static final Pattern EXPLICIT_CURRENCY_AMOUNT_PATTERN = Pattern.compile("(?i)(?:[₩￦]\\s*|KRW\\s*)?([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,9})\\s*(?:원|KRW)");
    private final AppUserService appUserService;
    private final LedgerOcrProperties properties;
    private final LedgerAiAnalysisProperties aiProperties;
    private final LedgerOcrRemoteClient remoteClient;
    private final LedgerImageAnalysisRequestRepository imageAnalysisRequestRepository;
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ObjectMapper objectMapper;
    private final UserNotificationService userNotificationService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    private LedgerOcrImageStorageService imageStorageService;

    @Autowired(required = false)
    @Qualifier("ledgerOcrTaskExecutor")
    private Executor ledgerOcrTaskExecutor;

    public LedgerOcrAnalyzeResponse startAnalyze(Long userId, MultipartFile file, String documentType, String clientRequestId, String prompt, boolean useExistingEntryStyle) {
        AppUser owner = appUserService.getRequiredUser(userId);
        try {
            validateReady();
            validateFile(file);

            String normalizedDocumentType = normalizeDocumentType(documentType);
            String normalizedClientRequestId = normalizeClientRequestId(clientRequestId);
            String normalizedUserPrompt = normalizeUserPrompt(prompt);
            String effectiveUserPrompt = buildEffectiveUserPrompt(owner.getId(), normalizedUserPrompt, useExistingEntryStyle);
            byte[] fileBytes = file.getBytes();
            MultipartFile backgroundFile = new StoredImageMultipartFile(
                    "file",
                    firstNonBlank(file.getOriginalFilename(), "ocr-image"),
                    firstNonBlank(file.getContentType(), "application/octet-stream"),
                    fileBytes
            );
            LedgerImageAnalysisRequest history = createImageAnalysisRequest(owner, backgroundFile, normalizedDocumentType, normalizedClientRequestId);
            storeImageForHistory(owner.getId(), history, backgroundFile);
            submitImageAnalysisTask(owner.getId(), history, backgroundFile, normalizedDocumentType, effectiveUserPrompt);
            return processingAnalyzeResponse(history);
        } catch (IOException exception) {
            throw new BadRequestException("Image file could not be read.");
        }
    }

    public LedgerOcrAnalyzeResponse startReanalyzeHistoryImage(
            Long userId,
            Long historyId,
            String documentType,
            String prompt,
            boolean useExistingEntryStyle
    ) {
        AppUser owner = appUserService.getRequiredUser(userId);
        LedgerImageAnalysisRequest history = imageAnalysisRequestRepository.findByIdAndOwnerId(historyId, owner.getId())
                .orElseThrow(() -> new BadRequestException("Image analysis history was not found."));
        LedgerOcrImageStorageService.StoredImageContent storedImage = loadStoredHistoryImage(history);
        String effectiveDocumentType = normalizeDocumentType(firstNonBlank(documentType, history.getDocumentType()));
        String clientRequestId = "image-analysis-rerun-" + history.getId() + "-" + System.currentTimeMillis();
        MultipartFile storedFile = new StoredImageMultipartFile(
                "file",
                firstNonBlank(history.getFileName(), storedImage.fileName(), "ocr-image"),
                firstNonBlank(history.getContentType(), storedImage.contentType(), "application/octet-stream"),
                storedImage.bytes()
        );
        return startAnalyze(owner.getId(), storedFile, effectiveDocumentType, clientRequestId, prompt, useExistingEntryStyle);
    }

    public LedgerOcrAnalyzeResponse analyze(Long userId, MultipartFile file, String documentType, String clientRequestId, String prompt) {
        return analyze(userId, file, documentType, clientRequestId, prompt, false);
    }

    public LedgerOcrAnalyzeResponse analyze(Long userId, MultipartFile file, String documentType, String clientRequestId, String prompt, boolean useExistingEntryStyle) {
        AppUser owner = appUserService.getRequiredUser(userId);
        Timer.Sample ocrRequestTimer = startOcrRequestTimer();
        LedgerImageAnalysisRequest history = null;

        try {
            validateReady();
            validateFile(file);

            String normalizedDocumentType = normalizeDocumentType(documentType);
            String normalizedClientRequestId = normalizeClientRequestId(clientRequestId);
            String normalizedUserPrompt = normalizeUserPrompt(prompt);
            String effectiveUserPrompt = buildEffectiveUserPrompt(owner.getId(), normalizedUserPrompt, useExistingEntryStyle);
            history = createImageAnalysisRequest(owner, file, normalizedDocumentType, normalizedClientRequestId);
            storeImageForHistory(owner.getId(), history, file);
            RemoteAnalyzeResponse remoteResponse = remoteClient.analyze(file, normalizedDocumentType, effectiveUserPrompt);
            String paymentCapturePlatform = resolvePaymentCapturePlatform(
                    firstNonBlank(remoteResponse.documentType(), normalizedDocumentType),
                    effectiveUserPrompt,
                    remoteResponse
            );
            List<RemoteParsedResult> parsedEntries = resolveParsedEntries(remoteResponse);
            RemoteParsedResult parsed = parsedEntries.isEmpty() ? remoteResponse.parsed() : parsedEntries.get(0);
            EntryType entryType = parsed != null && parsed.entryType() == EntryType.INCOME
                    ? EntryType.INCOME
                    : EntryType.EXPENSE;

            List<LedgerOcrLineItemResponse> lineItems = mapLineItems(parsed);
            LedgerOcrEntrySuggestionResponse suggestion = parsed == null ? null : buildSuggestion(owner, parsed, entryType, lineItems, paymentCapturePlatform);
            List<LedgerOcrEntrySuggestionResponse> suggestions = parsedEntries.stream()
                    .filter(Objects::nonNull)
                    .map(parsedEntry -> buildSuggestionForParsedEntry(owner, parsedEntry, paymentCapturePlatform))
                    .toList();
            List<String> validationWarnings = buildSuggestionValidationWarnings(suggestions);
            if (!validationWarnings.isEmpty()) {
                parsed = appendWarnings(parsed, validationWarnings);
            }
            if (suggestions.isEmpty() && suggestion != null) {
                suggestions = List.of(suggestion);
            }

            LedgerOcrAnalyzeResponse response = new LedgerOcrAnalyzeResponse(
                    history.getId(),
                    history.getClientRequestId(),
                    LedgerImageAnalysisStatus.COMPLETED.name(),
                    firstNonBlank(remoteResponse.documentType(), normalizedDocumentType),
                    remoteResponse.rawText() == null ? "" : remoteResponse.rawText(),
                    suggestion,
                    suggestions,
                    lineItems,
                    parsed != null ? parsed.confidence() : null,
                    resolveResponseWarnings(parsed, validationWarnings),
                    parsed != null ? limit(parsed.vendor(), MAX_TEXT_LENGTH) : null,
                    parsed != null ? limit(parsed.paymentMethodText(), MAX_TEXT_LENGTH) : null,
                    parsed != null ? limit(firstNonBlank(
                            parsed.categoryText(),
                            parsed.categoryGroupName(),
                            parsed.categoryDetailName()
                    ), MAX_TEXT_LENGTH) : null,
                    remoteResponse.timing() == null ? java.util.Map.of() : remoteResponse.timing(),
                    List.of(),
                    List.of()
            );
            completeImageAnalysisRequest(history, response);
            LedgerOcrAnalyzeResponse finalResponse = withCurrentHistoryStatus(response);
            recordOcrRequest(ocrRequestTimer, "success", "none");
            return finalResponse;
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

    private void submitImageAnalysisTask(
            Long userId,
            LedgerImageAnalysisRequest history,
            MultipartFile file,
            String normalizedDocumentType,
            String effectiveUserPrompt
    ) {
        Runnable task = () -> processImageAnalysisInBackground(userId, history.getId(), file, normalizedDocumentType, effectiveUserPrompt);
        try {
            if (ledgerOcrTaskExecutor == null) {
                CompletableFuture.runAsync(task);
            } else {
                CompletableFuture.runAsync(task, ledgerOcrTaskExecutor);
            }
        } catch (RuntimeException exception) {
            failImageAnalysisRequest(history, exception);
            throw new BadRequestException("Image analysis task could not be started.");
        }
    }

    private void processImageAnalysisInBackground(
            Long userId,
            Long historyId,
            MultipartFile file,
            String normalizedDocumentType,
            String effectiveUserPrompt
    ) {
        Timer.Sample ocrRequestTimer = startOcrRequestTimer();
        LedgerImageAnalysisRequest history = imageAnalysisRequestRepository.findByIdAndOwnerId(historyId, userId).orElse(null);
        if (history == null || history.getStatus() == LedgerImageAnalysisStatus.CANCELLED) {
            recordOcrRequest(ocrRequestTimer, "cancelled", "cancelled");
            return;
        }

        try {
            AppUser owner = appUserService.getRequiredUser(userId);
            RemoteAnalyzeResponse remoteResponse = remoteClient.analyze(file, normalizedDocumentType, effectiveUserPrompt);
            String paymentCapturePlatform = resolvePaymentCapturePlatform(
                    firstNonBlank(remoteResponse.documentType(), normalizedDocumentType),
                    effectiveUserPrompt,
                    remoteResponse
            );
            List<RemoteParsedResult> parsedEntries = resolveParsedEntries(remoteResponse);
            RemoteParsedResult parsed = parsedEntries.isEmpty() ? remoteResponse.parsed() : parsedEntries.get(0);
            EntryType entryType = parsed != null && parsed.entryType() == EntryType.INCOME
                    ? EntryType.INCOME
                    : EntryType.EXPENSE;

            List<LedgerOcrLineItemResponse> lineItems = mapLineItems(parsed);
            LedgerOcrEntrySuggestionResponse suggestion = parsed == null ? null : buildSuggestion(owner, parsed, entryType, lineItems, paymentCapturePlatform);
            List<LedgerOcrEntrySuggestionResponse> suggestions = parsedEntries.stream()
                    .filter(Objects::nonNull)
                    .map(parsedEntry -> buildSuggestionForParsedEntry(owner, parsedEntry, paymentCapturePlatform))
                    .toList();
            List<String> validationWarnings = buildSuggestionValidationWarnings(suggestions);
            if (!validationWarnings.isEmpty()) {
                parsed = appendWarnings(parsed, validationWarnings);
            }
            if (suggestions.isEmpty() && suggestion != null) {
                suggestions = List.of(suggestion);
            }

            LedgerOcrAnalyzeResponse response = new LedgerOcrAnalyzeResponse(
                    history.getId(),
                    history.getClientRequestId(),
                    LedgerImageAnalysisStatus.COMPLETED.name(),
                    firstNonBlank(remoteResponse.documentType(), normalizedDocumentType),
                    remoteResponse.rawText() == null ? "" : remoteResponse.rawText(),
                    suggestion,
                    suggestions,
                    lineItems,
                    parsed != null ? parsed.confidence() : null,
                    resolveResponseWarnings(parsed, validationWarnings),
                    parsed != null ? limit(parsed.vendor(), MAX_TEXT_LENGTH) : null,
                    parsed != null ? limit(parsed.paymentMethodText(), MAX_TEXT_LENGTH) : null,
                    parsed != null ? limit(firstNonBlank(
                            parsed.categoryText(),
                            parsed.categoryGroupName(),
                            parsed.categoryDetailName()
                    ), MAX_TEXT_LENGTH) : null,
                    remoteResponse.timing() == null ? java.util.Map.of() : remoteResponse.timing(),
                    List.of(),
                    List.of()
            );
            completeImageAnalysisRequest(history, response);
            recordOcrRequest(ocrRequestTimer, "success", "none");
        } catch (RuntimeException exception) {
            failImageAnalysisRequest(history, exception);
            String failureReason = ocrFailureReason(exception);
            recordOcrRequest(ocrRequestTimer, "failure", failureReason);
            notifyOcrFailure(userId, failureReason);
            log.warn("Ledger image analysis background task failed: historyId={}", historyId, exception);
        }
    }

    private LedgerOcrAnalyzeResponse processingAnalyzeResponse(LedgerImageAnalysisRequest history) {
        return new LedgerOcrAnalyzeResponse(
                history.getId(),
                history.getClientRequestId(),
                LedgerImageAnalysisStatus.PROCESSING.name(),
                history.getDocumentType(),
                "",
                null,
                List.of(),
                List.of(),
                null,
                List.of("Image analysis request accepted. The result will appear in analysis history when complete."),
                null,
                null,
                null,
                java.util.Map.of(),
                List.of(),
                List.of()
        );
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
                .orElseThrow(() -> new BadRequestException("이미지 분석 기록을 찾을 수 없습니다."));
    }

    public LedgerImageAnalysisHistoryResponse cancelHistory(Long userId, Long historyId) {
        appUserService.getRequiredUser(userId);
        LedgerImageAnalysisRequest history = imageAnalysisRequestRepository.findByIdAndOwnerId(historyId, userId)
                .orElseThrow(() -> new BadRequestException("이미지 분석 기록을 찾을 수 없습니다."));
        return cancelHistoryRecord(history);
    }

    public LedgerImageAnalysisHistoryResponse cancelHistoryByClientRequestId(Long userId, String clientRequestId) {
        appUserService.getRequiredUser(userId);
        String normalizedClientRequestId = normalizeClientRequestId(clientRequestId);
        if (normalizedClientRequestId == null) {
            throw new BadRequestException("clientRequestId is required.");
        }
        LedgerImageAnalysisRequest history = imageAnalysisRequestRepository.findByClientRequestIdAndOwnerId(normalizedClientRequestId, userId)
                .orElseThrow(() -> new BadRequestException("이미지 분석 기록을 찾을 수 없습니다."));
        return cancelHistoryRecord(history);
    }



    @Transactional
    public LedgerImageAnalysisHistoryDeleteResponse deleteHistory(Long userId, Long historyId) {
        appUserService.getRequiredUser(userId);
        LedgerImageAnalysisRequest history = imageAnalysisRequestRepository.findByIdAndOwnerId(historyId, userId)
                .orElseThrow(() -> new NotFoundException("Image analysis history was not found."));
        if (history.getStatus() == LedgerImageAnalysisStatus.PROCESSING) {
            throw new BadRequestException("Processing image analysis history cannot be deleted. Cancel it first.");
        }
        if (imageStorageService != null && hasStoredImage(history)) {
            imageStorageService.delete(history.getImageObjectKey());
        }
        imageAnalysisRequestRepository.delete(history);
        return new LedgerImageAnalysisHistoryDeleteResponse(1);
    }

    public LedgerOcrAnalyzeResponse reanalyzeHistoryImage(
            Long userId,
            Long historyId,
            String documentType,
            String prompt,
            boolean useExistingEntryStyle
    ) {
        AppUser owner = appUserService.getRequiredUser(userId);
        LedgerImageAnalysisRequest history = imageAnalysisRequestRepository.findByIdAndOwnerId(historyId, owner.getId())
                .orElseThrow(() -> new BadRequestException("Image analysis history was not found."));
        LedgerOcrImageStorageService.StoredImageContent storedImage = loadStoredHistoryImage(history);
        String effectiveDocumentType = normalizeDocumentType(firstNonBlank(documentType, history.getDocumentType()));
        String clientRequestId = "image-analysis-rerun-" + history.getId() + "-" + System.currentTimeMillis();
        MultipartFile storedFile = new StoredImageMultipartFile(
                "file",
                firstNonBlank(history.getFileName(), storedImage.fileName(), "ocr-image"),
                firstNonBlank(history.getContentType(), storedImage.contentType(), "application/octet-stream"),
                storedImage.bytes()
        );
        return analyze(owner.getId(), storedFile, effectiveDocumentType, clientRequestId, prompt, useExistingEntryStyle);
    }

    public LedgerOcrImageStorageService.StoredImageContent getHistoryImage(Long userId, Long historyId) {
        appUserService.getRequiredUser(userId);
        LedgerImageAnalysisRequest history = imageAnalysisRequestRepository.findByIdAndOwnerId(historyId, userId)
                .orElseThrow(() -> new BadRequestException("Image analysis history was not found."));
        return loadStoredHistoryImage(history);
    }

    @Transactional
    public LedgerImageAnalysisHistoryResponse markHistoryEntryApproved(Long userId, Long historyId, int entryIndex, Long entryId) {
        appUserService.getRequiredUser(userId);
        if (historyId == null) {
            throw new BadRequestException("이미지 분석 기록을 확인할 수 없습니다.");
        }
        if (entryIndex < 0) {
            throw new BadRequestException("승인한 거래 후보 번호가 올바르지 않습니다.");
        }
        LedgerImageAnalysisRequest history = imageAnalysisRequestRepository.findByIdAndOwnerId(historyId, userId)
                .orElseThrow(() -> new NotFoundException("이미지 분석 기록을 찾을 수 없습니다."));
        if (history.getStatus() != LedgerImageAnalysisStatus.COMPLETED) {
            throw new BadRequestException("완료된 이미지 분석 기록만 승인 표시할 수 있습니다.");
        }
        LedgerOcrAnalyzeResponse response = readResponseJson(history.getResultJson());
        if (response == null) {
            throw new BadRequestException("이미지 분석 결과를 확인할 수 없습니다.");
        }
        int suggestionCount = response.suggestedEntries() == null ? 0 : response.suggestedEntries().size();
        if (suggestionCount == 0 && response.suggestedEntry() != null) {
            suggestionCount = 1;
        }
        if (entryIndex >= suggestionCount) {
            throw new BadRequestException("승인한 거래 후보 번호가 분석 결과 범위를 벗어났습니다.");
        }
        LedgerOcrAnalyzeResponse updatedResponse = withApprovedEntry(response, entryIndex, entryId);
        history.setResultJson(writeResponseJson(updatedResponse));
        history.setSummary(limit(summarizeResponse(updatedResponse), 500));
        LedgerImageAnalysisRequest saved = imageAnalysisRequestRepository.save(history);
        return toHistoryResponse(saved);
    }

    private LedgerOcrAnalyzeResponse withApprovedEntry(LedgerOcrAnalyzeResponse response, int entryIndex, Long entryId) {
        Set<Integer> approvedIndexes = new LinkedHashSet<>(safeIntegerList(response.approvedEntryIndexes()));
        approvedIndexes.add(entryIndex);
        Set<Long> approvedIds = new LinkedHashSet<>(safeLongList(response.approvedEntryIds()));
        if (entryId != null && entryId > 0) {
            approvedIds.add(entryId);
        }
        return new LedgerOcrAnalyzeResponse(
                response.analysisId(),
                response.clientRequestId(),
                response.analysisStatus(),
                response.documentType(),
                response.rawText(),
                response.suggestedEntry(),
                response.suggestedEntries(),
                response.lineItems(),
                response.confidence(),
                response.warnings(),
                response.vendor(),
                response.paymentMethodText(),
                response.categoryText(),
                response.timing(),
                List.copyOf(approvedIndexes),
                List.copyOf(approvedIds)
        );
    }

    private List<Integer> safeIntegerList(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> value >= 0)
                .distinct()
                .toList();
    }

    private List<Long> safeLongList(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> value > 0)
                .distinct()
                .toList();
    }
    private LedgerImageAnalysisHistoryResponse cancelHistoryRecord(LedgerImageAnalysisRequest history) {
        if (history.getStatus() != LedgerImageAnalysisStatus.PROCESSING) {
            return toHistoryResponse(history);
        }
        history.setStatus(LedgerImageAnalysisStatus.CANCELLED);
        history.setCancelledAt(LocalDateTime.now());
        history.setSummary("사용자가 이미지 분석 요청을 취소했습니다.");
        imageAnalysisRequestRepository.save(history);
        return toHistoryResponse(history);
    }
    private void storeImageForHistory(Long ownerId, LedgerImageAnalysisRequest history, MultipartFile file) {
        if (history == null || imageStorageService == null || !imageStorageService.supportsStorage()) {
            return;
        }
        LedgerOcrImageStorageService.StoredImage storedImage = imageStorageService.store(ownerId, history.getId(), file);
        history.setImageObjectKey(storedImage.objectKey());
        history.setImageStoredAt(storedImage.storedAt());
        imageAnalysisRequestRepository.save(history);
    }

    private LedgerOcrImageStorageService.StoredImageContent loadStoredHistoryImage(LedgerImageAnalysisRequest history) {
        if (history == null || !isPresent(history.getImageObjectKey())) {
            throw new BadRequestException("Stored original image is not available for this analysis history.");
        }
        if (imageStorageService == null) {
            throw new BadRequestException("OCR image storage is not available.");
        }
        return imageStorageService.load(history.getImageObjectKey(), history.getFileName(), history.getContentType());
    }

    private boolean hasStoredImage(LedgerImageAnalysisRequest history) {
        return history != null && isPresent(history.getImageObjectKey());
    }

    private String imageUrlForHistory(LedgerImageAnalysisRequest history) {
        if (!hasStoredImage(history) || history.getId() == null) {
            return null;
        }
        return "/api/ledger/image-analysis/history/" + history.getId() + "/image";
    }

    private LedgerImageAnalysisRequest createImageAnalysisRequest(AppUser owner, MultipartFile file, String documentType, String clientRequestId) {
        LedgerImageAnalysisRequest history = new LedgerImageAnalysisRequest();
        history.setOwner(owner);
        history.setStatus(LedgerImageAnalysisStatus.PROCESSING);
        history.setDocumentType(documentType);
        history.setClientRequestId(clientRequestId);
        history.setFileName(limit(file.getOriginalFilename(), 260));
        history.setContentType(limit(file.getContentType(), 120));
        history.setFileSizeBytes(file.getSize());
        history.setSummary("AI 이미지 분석 요청을 처리 중입니다.");
        return imageAnalysisRequestRepository.save(history);
    }

    private void completeImageAnalysisRequest(LedgerImageAnalysisRequest history, LedgerOcrAnalyzeResponse response) {
        LedgerImageAnalysisRequest currentHistory = imageAnalysisRequestRepository.findById(history.getId()).orElse(history);
        if (currentHistory.getStatus() == LedgerImageAnalysisStatus.CANCELLED) {
            return;
        }
        currentHistory.setStatus(LedgerImageAnalysisStatus.COMPLETED);
        currentHistory.setCompletedAt(LocalDateTime.now());
        currentHistory.setDocumentType(normalizeDocumentType(firstNonBlank(response.documentType(), currentHistory.getDocumentType())));
        currentHistory.setRawText(response.rawText());
        currentHistory.setSummary(limit(summarizeResponse(response), 500));
        currentHistory.setResultJson(writeResponseJson(response));
        imageAnalysisRequestRepository.save(currentHistory);
    }

    private void failImageAnalysisRequest(LedgerImageAnalysisRequest history, RuntimeException exception) {
        LedgerImageAnalysisRequest currentHistory = imageAnalysisRequestRepository.findById(history.getId()).orElse(history);
        if (currentHistory.getStatus() == LedgerImageAnalysisStatus.CANCELLED) {
            return;
        }
        currentHistory.setStatus(LedgerImageAnalysisStatus.FAILED);
        currentHistory.setCompletedAt(LocalDateTime.now());
        currentHistory.setErrorMessage(limit(exception.getMessage(), 1000));
        currentHistory.setSummary("AI 이미지 분석 요청에 실패했습니다.");
        imageAnalysisRequestRepository.save(currentHistory);
    }

    private LedgerImageAnalysisHistoryResponse toHistoryResponse(LedgerImageAnalysisRequest history) {
        return new LedgerImageAnalysisHistoryResponse(
                history.getId(),
                history.getClientRequestId(),
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
                history.getCancelledAt(),
                hasStoredImage(history),
                imageUrlForHistory(history)
        );
    }

    private String summarizeResponse(LedgerOcrAnalyzeResponse response) {
        List<String> warnings = response.warnings() == null ? List.of() : response.warnings();
        LedgerOcrEntrySuggestionResponse entry = response.suggestedEntry();
        int count = response.suggestedEntries() == null ? 0 : response.suggestedEntries().size();
        if (entry == null) {
            String base = count > 0 ? count + "건 거래 후보 추출" : "추출된 거래 후보가 없습니다.";
            return warnings.isEmpty() ? base : base + " - 검수 필요";
        }
        String amount = entry.amount() == null ? "금액 확인 필요" : entry.amount().toPlainString() + " KRW";
        String title = firstNonBlank(entry.title(), "제목 확인 필요");
        String summary = title + " - " + amount + " - " + Math.max(count, 1) + "건";
        return warnings.isEmpty() ? summary : summary + " - 검수 필요";
    }

    private LedgerOcrAnalyzeResponse withCurrentHistoryStatus(LedgerOcrAnalyzeResponse response) {
        if (response == null || response.analysisId() == null) {
            return response;
        }
        LedgerImageAnalysisStatus currentStatus = imageAnalysisRequestRepository.findById(response.analysisId())
                .map(LedgerImageAnalysisRequest::getStatus)
                .orElse(null);
        if (currentStatus == null || currentStatus.name().equals(response.analysisStatus())) {
            return response;
        }
        return new LedgerOcrAnalyzeResponse(
                response.analysisId(),
                response.clientRequestId(),
                currentStatus.name(),
                response.documentType(),
                response.rawText(),
                response.suggestedEntry(),
                response.suggestedEntries(),
                response.lineItems(),
                response.confidence(),
                response.warnings(),
                response.vendor(),
                response.paymentMethodText(),
                response.categoryText(),
                response.timing(),
                safeIntegerList(response.approvedEntryIndexes()),
                safeLongList(response.approvedEntryIds())
        );
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
                    "AI_IMAGE_ANALYSIS_FAILED",
                    "AI 이미지 분석 실패",
                    "거래 이미지 분석을 완료하지 못했습니다. AI 서버 상태를 확인하거나 잠시 후 다시 시도해 주세요.",
                    "/calendar?receiptOcr=1",
                    "{\"reason\":\"" + failureReason + "\"}"
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to create ledger image analysis notification: userId={}, reason={}", userId, failureReason, exception);
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
            if ("AI 이미지 분석 기능이 꺼져 있거나 서버 설정이 누락되었습니다.".equals(message)) {
                return "not_configured";
            }
            if ("분석할 거래 이미지를 먼저 업로드해 주세요.".equals(message)
                    || "거래 이미지가 AI 분석 업로드 제한 용량을 초과했습니다.".equals(message)
                    || "이미지 파일만 분석할 수 있습니다.".equals(message)) {
                return "invalid_file";
            }
            return "bad_request";
        }
        return "runtime_error";
    }
    private String normalizeClientRequestId(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        return limit(clientRequestId.trim(), 120);
    }

    private String normalizeUserPrompt(String prompt) {
        if (!isPresent(prompt)) {
            return null;
        }
        String normalized = prompt.replace('\u0000', ' ').trim();
        return normalized.isBlank() ? null : limit(normalized, MAX_USER_PROMPT_LENGTH);
    }

    private String buildEffectiveUserPrompt(Long ownerId, String userPrompt, boolean useExistingEntryStyle) {
        List<String> sections = new ArrayList<>();
        addIfPresent(sections, userPrompt);
        addIfPresent(sections, buildCategoryCriteriaPrompt(ownerId));
        if (useExistingEntryStyle) {
            addIfPresent(sections, buildExistingEntryStylePrompt(ownerId));
        }
        return combinePromptSections(sections);
    }

    private String combinePromptSections(List<String> sections) {
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (String section : sections) {
            if (!isPresent(section)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            int remaining = MAX_USER_PROMPT_LENGTH - builder.length();
            if (remaining <= 0) {
                break;
            }
            builder.append(limit(section.trim(), remaining));
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    private String buildCategoryCriteriaPrompt(Long ownerId) {
        if (ownerId == null) {
            return null;
        }
        List<CategoryGroup> groups;
        try {
            groups = categoryGroupRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(ownerId);
        } catch (RuntimeException exception) {
            log.warn("Failed to load active category criteria for OCR prompt: userId={}", ownerId, exception);
            return null;
        }
        if (groups == null || groups.isEmpty()) {
            return null;
        }

        List<String> expenseLines = new ArrayList<>();
        List<String> incomeLines = new ArrayList<>();
        for (CategoryGroup group : groups) {
            if (group == null || !group.isActive() || !isPresent(group.getName())) {
                continue;
            }
            String line = formatCategoryCriteriaLine(group);
            if (!isPresent(line)) {
                continue;
            }
            if (group.getEntryType() == EntryType.INCOME) {
                incomeLines.add(line);
            } else {
                expenseLines.add(line);
            }
            if (expenseLines.size() + incomeLines.size() >= MAX_CATEGORY_CRITERIA_GROUPS) {
                break;
            }
        }
        if (expenseLines.isEmpty() && incomeLines.isEmpty()) {
            return null;
        }

        List<String> sections = new ArrayList<>();
        if (!expenseLines.isEmpty()) {
            sections.add("지출:\n" + String.join("\n", expenseLines));
        }
        if (!incomeLines.isEmpty()) {
            sections.add("수입:\n" + String.join("\n", incomeLines));
        }
        return "[현재 사용자 분류 기준]\n"
                + "아래 목록은 이 사용자가 현재 사용 중인 활성 대분류/분류입니다. "
                + "categoryGroupName/categoryDetailName은 가능하면 아래 정확한 이름 중에서만 선택하세요. "
                + "거래 구분이 지출이면 지출 목록에서, 수입이면 수입 목록에서만 고르세요. "
                + "대분류만 맞고 상세분류가 애매하면 categoryGroupName만 채우고 categoryDetailName은 빈 문자열로 두세요. "
                + "맞는 항목이 없거나 근거가 부족하면 새 분류명을 만들지 말고 두 필드를 빈 문자열로 두세요. "
                + "미분류는 맞는 분류가 없을 때만 사용하세요.\n"
                + String.join("\n", sections);
    }

    private String formatCategoryCriteriaLine(CategoryGroup group) {
        String groupName = compactPromptValue(group.getName(), MAX_CATEGORY_CRITERIA_NAME_LENGTH);
        if (!isPresent(groupName)) {
            return null;
        }
        List<String> detailNames = activeCategoryDetailNames(group);
        if (detailNames.isEmpty()) {
            return "- " + groupName;
        }
        return "- " + groupName + ": " + String.join(", ", detailNames);
    }

    private List<String> activeCategoryDetailNames(CategoryGroup group) {
        if (group == null || group.getId() == null) {
            return List.of();
        }
        List<CategoryDetail> details;
        try {
            details = categoryDetailRepository.findAllByGroupIdAndActiveTrueOrderByDisplayOrderAscIdAsc(group.getId());
        } catch (RuntimeException exception) {
            log.warn("Failed to load active category details for OCR prompt: groupId={}", group.getId(), exception);
            return List.of();
        }
        if (details == null || details.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (CategoryDetail detail : details) {
            if (detail == null || !detail.isActive()) {
                continue;
            }
            String name = compactPromptValue(detail.getName(), MAX_CATEGORY_CRITERIA_NAME_LENGTH);
            if (isPresent(name) && names.stream().noneMatch(existing -> existing.equalsIgnoreCase(name))) {
                names.add(name);
            }
            if (names.size() >= MAX_CATEGORY_CRITERIA_DETAILS_PER_GROUP) {
                break;
            }
        }
        return names;
    }

    private String buildExistingEntryStylePrompt(Long ownerId) {
        if (ownerId == null) {
            return null;
        }
        List<ExistingEntryStyleAggregate> examples;
        try {
            examples = ledgerEntryRepository.findRecentEntriesForOcrStyle(
                    ownerId,
                    PageRequest.of(0, EXISTING_ENTRY_STYLE_EXAMPLE_LIMIT)
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to load existing ledger entry style examples for OCR prompt: userId={}", ownerId, exception);
            return null;
        }
        if (examples == null || examples.isEmpty()) {
            return null;
        }

        List<String> lines = new ArrayList<>();
        for (ExistingEntryStyleAggregate example : examples) {
            String line = formatExistingEntryStyleExample(example, lines.size() + 1);
            if (isPresent(line)) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        return "[현재 입력데이터 기반 데이터 추출 보정]\n"
                + "사용자가 이 옵션을 켰습니다. 아래 예시는 이 사용자의 기존 가계부 입력 형식입니다. "
                + "이미지에서 확인되는 사실이 최우선이며, 예시의 날짜/금액/결제수단을 복사하지 마세요. "
                + "유사한 상호나 서비스가 보일 때 제목 접두어, 메모 작성 방식, 대분류/분류 명칭만 참고하세요. "
                + "확실하지 않으면 보정하지 말고 원문 기반 추출, 빈 결제수단, 미분류 또는 빈 분류명을 유지하세요.\n"
                + "기존 입력 예시:\n"
                + String.join("\n", lines);
    }

    private String formatExistingEntryStyleExample(ExistingEntryStyleAggregate example, int index) {
        if (example == null || !isPresent(example.getTitle())) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        fields.add("제목=" + compactPromptValue(example.getTitle(), MAX_STYLE_FIELD_LENGTH));
        fields.add("구분=" + (example.getEntryType() == EntryType.INCOME ? "수입" : "지출"));
        if (example.getAmount() != null) {
            fields.add("금액=" + formatWon(example.getAmount()) + "원");
        }
        String categoryGroup = compactPromptValue(example.getCategoryGroupName(), 40);
        String categoryDetail = compactPromptValue(example.getCategoryDetailName(), 40);
        if (isPresent(categoryGroup) || isPresent(categoryDetail)) {
            fields.add("분류=" + firstNonBlank(categoryGroup, "미분류") + (isPresent(categoryDetail) ? "/" + categoryDetail : ""));
        }
        String paymentMethod = compactPromptValue(example.getPaymentMethodName(), 50);
        if (isPresent(paymentMethod)) {
            fields.add("결제수단=" + paymentMethod);
        }
        String memo = compactPromptValue(example.getMemo(), MAX_STYLE_MEMO_LENGTH);
        if (isPresent(memo)) {
            fields.add("메모=" + memo);
        }
        return index + ". " + String.join(" | ", fields);
    }

    private String compactPromptValue(String value, int maxLength) {
        if (!isPresent(value)) {
            return "";
        }
        String normalized = value.replace('\u0000', ' ').replaceAll("\\s+", " ").trim();
        return limit(normalized, maxLength);
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

    private List<String> resolveResponseWarnings(RemoteParsedResult parsed, List<String> validationWarnings) {
        List<String> warnings = new ArrayList<>();
        if (parsed != null && parsed.warnings() != null) {
            warnings.addAll(parsed.warnings());
        }
        if (validationWarnings != null) {
            for (String warning : validationWarnings) {
                if (isPresent(warning) && !warnings.contains(warning)) {
                    warnings.add(warning);
                }
            }
        }
        String safetyWarning = "AI 분석 결과는 자동 저장되지 않습니다. 사용자가 검수 후 거래 등록을 확정해야 합니다.";
        if (!warnings.contains(safetyWarning)) {
            warnings.add(safetyWarning);
        }
        return warnings;
    }

    private List<String> buildSuggestionValidationWarnings(List<LedgerOcrEntrySuggestionResponse> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of("AI가 입력 가능한 거래 후보를 찾지 못했습니다.");
        }
        List<String> warnings = new ArrayList<>();
        for (LedgerOcrEntrySuggestionResponse suggestion : suggestions) {
            if (suggestion == null) {
                continue;
            }
            if (!isPresent(suggestion.title())) {
                warnings.add("일부 거래 후보의 제목을 확인해야 합니다.");
            }
            if (suggestion.amount() == null || suggestion.amount().compareTo(BigDecimal.ZERO) <= 0) {
                warnings.add("일부 거래 후보의 금액을 확인해야 합니다.");
            }
        }
        return warnings.stream().distinct().toList();
    }

    private RemoteParsedResult appendWarnings(RemoteParsedResult parsed, List<String> extraWarnings) {
        if (parsed == null || extraWarnings == null || extraWarnings.isEmpty()) {
            return parsed;
        }
        List<String> warnings = new ArrayList<>();
        if (parsed.warnings() != null) {
            warnings.addAll(parsed.warnings());
        }
        for (String warning : extraWarnings) {
            if (isPresent(warning) && !warnings.contains(warning)) {
                warnings.add(warning);
            }
        }
        return new RemoteParsedResult(
                parsed.entryDate(),
                parsed.entryTime(),
                parsed.entryType(),
                parsed.title(),
                parsed.memo(),
                parsed.amount(),
                parsed.vendor(),
                parsed.paymentMethodText(),
                parsed.categoryGroupName(),
                parsed.categoryDetailName(),
                parsed.categoryText(),
                parsed.lineItems(),
                parsed.confidence(),
                warnings
        );
    }

    private LedgerOcrEntrySuggestionResponse buildSuggestionForParsedEntry(AppUser owner, RemoteParsedResult parsed, String paymentCapturePlatform) {
        EntryType entryType = parsed != null && parsed.entryType() == EntryType.INCOME
                ? EntryType.INCOME
                : EntryType.EXPENSE;
        return buildSuggestion(owner, parsed, entryType, mapLineItems(parsed), paymentCapturePlatform);
    }

    private void validateReady() {
        if (!aiProperties.isConfigured() || !aiProperties.isLmStudioConfigured()) {
            throw new BadRequestException("AI 이미지 분석 기능이 꺼져 있거나 서버 설정이 누락되었습니다.");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("분석할 거래 이미지를 먼저 업로드해 주세요.");
        }
        if (file.getSize() > properties.getMaxFileSize().toBytes()) {
            throw new BadRequestException("거래 이미지가 AI 분석 업로드 제한 용량을 초과했습니다.");
        }

        String contentType = normalizeContentType(file.getContentType());
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String extension = resolveImageExtension(fileName);
        if (extension.isBlank()
                || !isAllowedImageContentType(extension, contentType)
                || !hasAllowedImageSignature(extension, file)) {
            throw new BadRequestException("이미지 파일만 분석할 수 있습니다.");
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

    private String resolvePaymentCapturePlatform(String documentType, String userPrompt, RemoteAnalyzeResponse response) {
        if (!"PAYMENT_CAPTURE".equalsIgnoreCase(firstNonBlank(documentType, ""))) {
            return null;
        }

        List<String> clues = new ArrayList<>();
        addIfPresent(clues, userPrompt);
        if (response != null) {
            addIfPresent(clues, response.rawText());
            addRemoteParsedPlatformClues(clues, response.parsed());
            if (response.parsedEntries() != null) {
                response.parsedEntries().forEach(parsed -> addRemoteParsedPlatformClues(clues, parsed));
            }
        }

        String joined = String.join(" ", clues);
        if (containsNaverPayClue(joined)) {
            return "네이버페이";
        }
        return null;
    }

    private void addRemoteParsedPlatformClues(List<String> clues, RemoteParsedResult parsed) {
        if (parsed == null) {
            return;
        }
        addIfPresent(clues, parsed.title());
        addIfPresent(clues, parsed.vendor());
        addIfPresent(clues, parsed.memo());
        addIfPresent(clues, parsed.categoryText());
        if (parsed.lineItems() != null) {
            parsed.lineItems().forEach(item -> addIfPresent(clues, item == null ? null : item.itemName()));
        }
    }

    private boolean containsNaverPayClue(String value) {
        return containsIgnoreCase(value, "네이버페이")
                || containsIgnoreCase(value, "네이버 페이")
                || containsIgnoreCase(value, "naver pay")
                || containsIgnoreCase(value, "n pay")
                || containsIgnoreCase(value, "npay")
                || containsIgnoreCase(value, "n+ membership")
                || containsIgnoreCase(value, "n+");
    }

    private String normalizeOcrTitle(RemoteParsedResult parsed, List<LedgerOcrLineItemResponse> lineItems, String paymentCapturePlatform) {
        String rawTitle = firstNonBlank(
                parsed != null ? parsed.title() : null,
                parsed != null ? parsed.vendor() : null,
                lineItems.isEmpty() ? null : lineItems.get(0).itemName(),
                "Receipt"
        );
        String title = cleanupPaymentCaptureTitle(rawTitle);
        String firstItemName = lineItems.isEmpty() ? null : cleanupPaymentCaptureTitle(lineItems.get(0).itemName());
        if (!isPresent(title)) {
            title = firstNonBlank(
                    firstItemName,
                    parsed != null ? parsed.vendor() : null,
                    "Receipt"
            );
        }
        if (isGenericOcrTitle(title) && isPresent(firstItemName) && !isGenericOcrTitle(firstItemName)) {
            title = firstItemName;
        }
        if (isPresent(paymentCapturePlatform) && !hasPlatformPrefix(title, paymentCapturePlatform)) {
            title = paymentCapturePlatform + " : " + title;
        }
        return title;
    }

    private String cleanupPaymentCaptureTitle(String value) {
        if (!isPresent(value)) {
            return value;
        }
        String cleaned = value
                .replace('\u203A', '>')
                .replace('\u3009', '>')
                .replace('\uFF1E', '>')
                .trim();
        int arrowIndex = cleaned.indexOf('>');
        if (arrowIndex >= 0) {
            cleaned = cleaned.substring(0, arrowIndex).trim();
        }
        for (String marker : List.of(
                "구매확정",
                "결제완료",
                "승인완료",
                "취소됨",
                "리뷰쓰기",
                "다시 담기"
        )) {
            int markerIndex = cleaned.indexOf(marker);
            if (markerIndex > 0) {
                cleaned = cleaned.substring(0, markerIndex).trim();
            } else if (markerIndex == 0) {
                cleaned = "";
            }
        }
        for (String suffix : List.of("구매건", "결제건")) {
            if (cleaned.endsWith(suffix)) {
                cleaned = cleaned.substring(0, cleaned.length() - suffix.length()).trim();
            }
        }
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private boolean isGenericOcrTitle(String title) {
        if (!isPresent(title)) {
            return true;
        }
        String normalized = title
                .replaceAll("[\\s:_/\\-.]+", "")
                .toLowerCase(Locale.ROOT);
        if (!isPresent(normalized)) {
            return true;
        }
        if (List.of(
                "receipt",
                "salesslip",
                "cardtype",
                "cardno",
                "taxable",
                "vat",
                "taxfree",
                "total",
                "hyundaicard",
                "\uD604\uB300\uCE74\uB4DC",
                "\uD569\uACC4",
                "\uACB0\uC81C\uAE08\uC561",
                "\uACFC\uC138\uAE08\uC561",
                "\uBD80\uAC00\uC138",
                "\uC2B9\uC778\uBC88\uD638"
        ).contains(normalized)) {
            return true;
        }
        return normalized.contains("salesslip")
                || normalized.contains("cardtransaction")
                || normalized.contains("cardno")
                || normalized.contains("taxable")
                || normalized.contains("\uCE74\uB4DC\uAC70\uB798\uB0B4\uC5ED\uD655\uC778\uC11C")
                || normalized.contains("\uAC70\uB798\uB0B4\uC5ED\uD655\uC778\uC11C")
                || normalized.contains("\uCE74\uB4DC\uB9E4\uCD9C\uC804\uD45C");
    }
    private boolean hasPlatformPrefix(String title, String platform) {
        if (!isPresent(title) || !isPresent(platform)) {
            return false;
        }
        String normalizedTitle = title.replace(" ", "").toLowerCase(Locale.ROOT);
        String normalizedPlatform = platform.replace(" ", "").toLowerCase(Locale.ROOT);
        return normalizedTitle.startsWith(normalizedPlatform + ":")
                || normalizedTitle.startsWith(normalizedPlatform + "\uFF1A");
    }

    private boolean containsIgnoreCase(String value, String needle) {
        if (!isPresent(value) || !isPresent(needle)) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
    private LedgerOcrEntrySuggestionResponse buildSuggestion(
            AppUser owner,
            RemoteParsedResult parsed,
            EntryType entryType,
            List<LedgerOcrLineItemResponse> lineItems,
            String paymentCapturePlatform
    ) {
        LocalDate entryDate = parsed == null ? null : parsed.entryDate();
        LocalTime entryTime = parsed == null ? null : parsed.entryTime();
        BigDecimal amount = resolveOcrAmount(parsed, lineItems);
        String title = limit(normalizeOcrTitle(parsed, lineItems, paymentCapturePlatform), 120);
        String memo = limit(buildMemo(parsed, lineItems), MAX_TEXT_LENGTH);
        ResolvedOcrCategory category = resolveOcrCategory(owner, entryType, parsed);

        return new LedgerOcrEntrySuggestionResponse(
                entryDate,
                entryTime,
                title,
                memo,
                amount,
                entryType,
                category.groupId(),
                category.groupName(),
                category.detailId(),
                category.detailName(),
                null,
                null
        );
    }

    private BigDecimal resolveOcrAmount(RemoteParsedResult parsed, List<LedgerOcrLineItemResponse> lineItems) {
        if (parsed == null) {
            return null;
        }
        BigDecimal parsedAmount = positiveAmount(parsed.amount());
        boolean orderHistory = looksLikeOrderHistory(parsed);
        BigDecimal orderHistoryAmount = resolveOrderHistoryAmount(parsed, lineItems, parsedAmount);
        if (orderHistoryAmount != null) {
            return orderHistoryAmount;
        }
        BigDecimal anchoredAmount = orderHistory
                ? firstAmountFromPatterns(ocrAmountSourceTexts(parsed), FINAL_TOTAL_AMOUNT_PATTERNS)
                : firstAnchoredAmount(parsed);
        if (anchoredAmount != null) {
            return anchoredAmount;
        }
        BigDecimal lineItemAmount = singleLineItemAmount(lineItems);
        if (lineItemAmount != null && (parsedAmount == null || (orderHistory && parsedAmount.compareTo(lineItemAmount) != 0 && amountAppearsInParsedText(parsed, lineItemAmount)))) {
            return lineItemAmount;
        }
        return parsedAmount;
    }

    private BigDecimal resolveOrderHistoryAmount(RemoteParsedResult parsed, List<LedgerOcrLineItemResponse> lineItems, BigDecimal parsedAmount) {
        if (!looksLikeOrderHistory(parsed)) {
            return null;
        }
        List<String> texts = ocrAmountSourceTexts(parsed);
        BigDecimal nearbyAmount = explicitCurrencyAmountNearEntry(parsed, lineItems, texts);
        if (nearbyAmount != null) {
            return nearbyAmount;
        }
        List<BigDecimal> explicitAmounts = distinctExplicitCurrencyAmounts(texts);
        if (explicitAmounts.size() == 1) {
            return explicitAmounts.get(0);
        }
        BigDecimal lineItemAmount = singleLineItemAmount(lineItems);
        if (lineItemAmount != null
                && (parsedAmount == null || parsedAmount.compareTo(lineItemAmount) != 0)
                && amountAppearsInParsedText(parsed, lineItemAmount)) {
            return lineItemAmount;
        }
        return null;
    }

    private BigDecimal firstAnchoredAmount(RemoteParsedResult parsed) {
        List<String> texts = ocrAmountSourceTexts(parsed);
        BigDecimal amount = firstAmountFromPatterns(texts, FINAL_TOTAL_AMOUNT_PATTERNS);
        if (amount != null) {
            return amount;
        }
        amount = firstAmountFromPatterns(texts, ORDER_ROW_AMOUNT_PATTERNS);
        if (amount != null) {
            return amount;
        }
        return firstAmountFromPatterns(texts, PAYMENT_AMOUNT_PATTERNS);
    }

    private List<String> ocrAmountSourceTexts(RemoteParsedResult parsed) {
        List<String> texts = new ArrayList<>();
        if (parsed == null) {
            return texts;
        }
        addIfPresent(texts, parsed.memo());
        addIfPresent(texts, parsed.title());
        addIfPresent(texts, parsed.vendor());
        addIfPresent(texts, parsed.categoryText());
        return texts;
    }

    private BigDecimal explicitCurrencyAmountNearEntry(
            RemoteParsedResult parsed,
            List<LedgerOcrLineItemResponse> lineItems,
            List<String> texts
    ) {
        List<String> keywords = entryAmountKeywords(parsed, lineItems);
        if (keywords.isEmpty()) {
            return null;
        }
        BigDecimal bestAmount = null;
        int bestScore = 0;
        for (String text : texts) {
            if (!isPresent(text)) {
                continue;
            }
            Matcher matcher = EXPLICIT_CURRENCY_AMOUNT_PATTERN.matcher(text);
            while (matcher.find()) {
                BigDecimal amount = parseWonAmount(matcher.group(1));
                if (amount == null) {
                    continue;
                }
                int score = keywordScoreNearAmount(text, matcher.start(), keywords);
                if (score > bestScore) {
                    bestScore = score;
                    bestAmount = amount;
                }
            }
        }
        return bestScore > 0 ? bestAmount : null;
    }

    private List<BigDecimal> distinctExplicitCurrencyAmounts(List<String> texts) {
        List<BigDecimal> amounts = new ArrayList<>();
        for (String text : texts) {
            if (!isPresent(text)) {
                continue;
            }
            Matcher matcher = EXPLICIT_CURRENCY_AMOUNT_PATTERN.matcher(text);
            while (matcher.find()) {
                BigDecimal amount = parseWonAmount(matcher.group(1));
                if (amount != null && amounts.stream().noneMatch(existing -> existing.compareTo(amount) == 0)) {
                    amounts.add(amount);
                }
            }
        }
        return amounts;
    }

    private List<String> entryAmountKeywords(RemoteParsedResult parsed, List<LedgerOcrLineItemResponse> lineItems) {
        List<String> keywords = new ArrayList<>();
        if (parsed != null) {
            addAmountKeywords(keywords, parsed.title());
        }
        if (lineItems != null) {
            lineItems.stream()
                    .filter(Objects::nonNull)
                    .map(LedgerOcrLineItemResponse::itemName)
                    .forEach(value -> addAmountKeywords(keywords, value));
        }
        return keywords;
    }

    private void addAmountKeywords(List<String> keywords, String value) {
        if (!isPresent(value)) {
            return;
        }
        String[] tokens = value.split("[^0-9A-Za-z가-힣]+");
        for (String token : tokens) {
            String normalized = normalizeAmountKeyword(token);
            if (normalized.length() < 2 || isCommonAmountKeyword(normalized) || normalized.matches("[0-9]+")) {
                continue;
            }
            if (keywords.stream().noneMatch(existing -> existing.equals(normalized))) {
                keywords.add(normalized);
            }
        }
    }

    private int keywordScoreNearAmount(String text, int amountStart, List<String> keywords) {
        int from = Math.max(0, amountStart - 220);
        String window = normalizeAmountKeyword(text.substring(from, amountStart));
        int score = 0;
        for (String keyword : keywords) {
            if (window.contains(keyword)) {
                score += keyword.length() >= 4 ? 2 : 1;
            }
        }
        return score;
    }

    private String normalizeAmountKeyword(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z가-힣]", "");
    }

    private boolean isCommonAmountKeyword(String value) {
        return List.of(
                "네이버페이",
                "naver",
                "pay",
                "결제",
                "결제완료",
                "구매",
                "구매확정",
                "구매확정완료",
                "주문",
                "상품",
                "상품명",
                "상품정보",
                "금액",
                "배송",
                "배송조회",
                "상세보기",
                "리뷰쓰기",
                "다시담기"
        ).contains(value);
    }

    private BigDecimal firstAmountFromPatterns(List<String> texts, List<Pattern> patterns) {
        for (String text : texts) {
            if (!isPresent(text)) {
                continue;
            }
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    BigDecimal amount = parseWonAmount(matcher.group(1));
                    if (amount != null) {
                        return amount;
                    }
                }
            }
        }
        return null;
    }

    private BigDecimal singleLineItemAmount(List<LedgerOcrLineItemResponse> lineItems) {
        if (lineItems == null || lineItems.size() != 1) {
            return null;
        }
        return positiveAmount(lineItems.get(0).price());
    }

    private BigDecimal positiveAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal normalized = amount.abs();
        return normalized.compareTo(BigDecimal.ZERO) > 0 ? normalized : null;
    }

    private BigDecimal parseWonAmount(String value) {
        if (!isPresent(value)) {
            return null;
        }
        try {
            return positiveAmount(new BigDecimal(value.replace(",", "").trim()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean amountAppearsInParsedText(RemoteParsedResult parsed, BigDecimal amount) {
        if (parsed == null || amount == null) {
            return false;
        }
        String formatted = formatWon(amount);
        String plain = amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
        String joined = String.join(" ", List.of(
                firstNonBlank(parsed.title()),
                firstNonBlank(parsed.memo()),
                firstNonBlank(parsed.vendor()),
                firstNonBlank(parsed.categoryText())
        ));
        return containsIgnoreCase(joined, formatted + "원")
                || containsIgnoreCase(joined, formatted + " KRW")
                || containsIgnoreCase(joined, plain + "원")
                || containsIgnoreCase(joined, plain + " KRW");
    }

    private boolean looksLikeOrderHistory(RemoteParsedResult parsed) {
        if (parsed == null) {
            return false;
        }
        String joined = String.join(" ", List.of(
                firstNonBlank(parsed.title()),
                firstNonBlank(parsed.memo()),
                firstNonBlank(parsed.vendor()),
                firstNonBlank(parsed.categoryText())
        ));
        return containsIgnoreCase(joined, "주문")
                || containsIgnoreCase(joined, "상품금액")
                || containsIgnoreCase(joined, "구매확정")
                || containsIgnoreCase(joined, "배송조회")
                || containsIgnoreCase(joined, "주문처")
                || containsIgnoreCase(joined, "판매자")
                || containsIgnoreCase(joined, "order")
                || containsIgnoreCase(joined, "product amount");
    }
    private ResolvedOcrCategory resolveOcrCategory(AppUser owner, EntryType entryType, RemoteParsedResult parsed) {
        if (owner == null || owner.getId() == null) {
            return new ResolvedOcrCategory(null, null, null, null);
        }
        EntryType resolvedEntryType = entryType == EntryType.INCOME ? EntryType.INCOME : EntryType.EXPENSE;
        CategoryGroup group = findActiveCategoryGroup(owner.getId(), resolvedEntryType, parsed == null ? null : parsed.categoryGroupName());
        if (group == null) {
            group = resolveUncategorizedCategoryGroup(owner, resolvedEntryType);
        }
        CategoryDetail detail = findActiveCategoryDetail(group, parsed == null ? null : parsed.categoryDetailName());
        return new ResolvedOcrCategory(
                group == null ? null : group.getId(),
                group == null ? null : group.getName(),
                detail == null ? null : detail.getId(),
                detail == null ? null : detail.getName()
        );
    }

    private CategoryGroup findActiveCategoryGroup(Long ownerId, EntryType entryType, String categoryGroupName) {
        if (ownerId == null || !isPresent(categoryGroupName)) {
            return null;
        }
        return categoryGroupRepository.findFirstByOwnerIdAndEntryTypeAndNameIgnoreCaseOrderByIdAsc(
                        ownerId,
                        entryType,
                        categoryGroupName.trim()
                )
                .filter(CategoryGroup::isActive)
                .orElse(null);
    }

    private CategoryGroup resolveUncategorizedCategoryGroup(AppUser owner, EntryType entryType) {
        return categoryGroupRepository.findAllByOwnerIdAndEntryTypeOrderByDisplayOrderAscIdAsc(owner.getId(), entryType)
                .stream()
                .filter(group -> UNCATEGORIZED_CATEGORY_NAME.equalsIgnoreCase(group.getName()))
                .findFirst()
                .map(group -> {
                    if (!group.isActive()) {
                        group.setActive(true);
                        return categoryGroupRepository.save(group);
                    }
                    return group;
                })
                .orElseGet(() -> createUncategorizedCategoryGroup(owner, entryType));
    }

    private CategoryGroup createUncategorizedCategoryGroup(AppUser owner, EntryType entryType) {
        CategoryGroup group = new CategoryGroup();
        group.setOwner(owner);
        group.setName(UNCATEGORIZED_CATEGORY_NAME);
        group.setEntryType(entryType);
        group.setDisplayOrder(Integer.MAX_VALUE);
        group.setActive(true);
        return categoryGroupRepository.save(group);
    }

    private CategoryDetail findActiveCategoryDetail(CategoryGroup group, String categoryDetailName) {
        if (group == null || group.getId() == null || !isPresent(categoryDetailName)) {
            return null;
        }
        return categoryDetailRepository.findFirstByGroupIdAndNameIgnoreCaseOrderByIdAsc(group.getId(), categoryDetailName.trim())
                .filter(CategoryDetail::isActive)
                .orElse(null);
    }

    private String buildMemo(RemoteParsedResult parsed, List<LedgerOcrLineItemResponse> lineItems) {
        if (parsed == null) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        if (!lineItems.isEmpty()) {
            String itemSummary = lineItems.stream()
                    .limit(8)
                    .map(this::formatLineItem)
                    .filter(this::isPresent)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            addIfPresent(parts, itemSummary);
        }
        addIfPresent(parts, parsed.memo());
        return parts.isEmpty() ? null : String.join(" / ", parts);
    }

    private String formatLineItem(LedgerOcrLineItemResponse item) {
        if (item == null || !isPresent(item.itemName())) {
            return "";
        }
        StringBuilder builder = new StringBuilder(item.itemName().trim());
        if (item.quantity() != null && item.quantity().compareTo(BigDecimal.ONE) != 0) {
            builder.append(" x").append(formatQuantity(item.quantity()));
            if (isPresent(item.unit())) {
                builder.append(item.unit().trim());
            }
        }
        if (item.price() != null && item.price().compareTo(BigDecimal.ZERO) > 0) {
            builder.append('(').append(formatWon(item.price())).append("\uC6D0)");
        }
        return builder.toString();
    }

    private String formatQuantity(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    private String formatWon(BigDecimal amount) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(amount.setScale(0, RoundingMode.HALF_UP));
    }

    private void addIfPresent(List<String> parts, String value) {
        if (!isPresent(value)) {
            return;
        }
        String trimmed = value.trim();
        if (parts.stream().noneMatch(existing -> existing.equalsIgnoreCase(trimmed))) {
            parts.add(trimmed);
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


    private record ResolvedOcrCategory(
            Long groupId,
            String groupName,
            Long detailId,
            String detailName
    ) {
    }

    private static class StoredImageMultipartFile implements MultipartFile {

        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] bytes;

        private StoredImageMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            throw new UnsupportedOperationException("Stored OCR image files are not transferred to disk.");
        }
    }
}