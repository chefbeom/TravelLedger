package com.playdata.calen.ledger.ocr;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.dto.LedgerOcrAnalyzeResponse;
import com.playdata.calen.ledger.dto.LedgerOcrEntrySuggestionResponse;
import com.playdata.calen.ledger.dto.LedgerOcrLineItemResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteAnalyzeResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteLineItem;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteParsedResult;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.math.BigDecimal;
import java.text.Normalizer;
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
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final PaymentMethodRepository paymentMethodRepository;

    public LedgerOcrAnalyzeResponse analyze(Long userId, MultipartFile file) {
        appUserService.getRequiredUser(userId);
        validateReady();
        validateFile(file);

        RemoteAnalyzeResponse remoteResponse = remoteClient.analyze(file);
        RemoteParsedResult parsed = remoteResponse.parsed();
        EntryType entryType = parsed != null && parsed.entryType() == EntryType.INCOME
                ? EntryType.INCOME
                : EntryType.EXPENSE;

        MatchedCategory category = matchCategory(userId, entryType, parsed);
        PaymentMethod paymentMethod = matchPaymentMethod(userId, parsed);
        List<LedgerOcrLineItemResponse> lineItems = mapLineItems(parsed);
        LedgerOcrEntrySuggestionResponse suggestion = buildSuggestion(parsed, entryType, category, paymentMethod, lineItems);

        return new LedgerOcrAnalyzeResponse(
                remoteResponse.rawText() == null ? "" : remoteResponse.rawText(),
                suggestion,
                lineItems,
                parsed != null ? parsed.confidence() : null,
                parsed != null && parsed.warnings() != null ? parsed.warnings() : List.of(),
                parsed != null ? limit(parsed.vendor(), MAX_TEXT_LENGTH) : null,
                parsed != null ? limit(parsed.paymentMethodText(), MAX_TEXT_LENGTH) : null,
                parsed != null ? limit(firstNonBlank(parsed.categoryText(), parsed.categoryGroupName(), parsed.categoryDetailName()), MAX_TEXT_LENGTH) : null,
                remoteResponse.timing() == null ? java.util.Map.of() : remoteResponse.timing()
        );
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
            MatchedCategory category,
            PaymentMethod paymentMethod,
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
                category.group() != null ? category.group().getId() : null,
                category.group() != null ? category.group().getName() : null,
                category.detail() != null ? category.detail().getId() : null,
                category.detail() != null ? category.detail().getName() : null,
                paymentMethod != null ? paymentMethod.getId() : null,
                paymentMethod != null ? paymentMethod.getName() : null
        );
    }

    private String buildMemo(RemoteParsedResult parsed, List<LedgerOcrLineItemResponse> lineItems) {
        if (parsed == null) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        addIfPresent(parts, parsed.memo());
        if (isPresent(parsed.vendor()) && parts.stream().noneMatch((item) -> item.contains(parsed.vendor()))) {
            parts.add("Store: " + parsed.vendor());
        }
        if (!lineItems.isEmpty()) {
            String itemSummary = lineItems.stream()
                    .limit(5)
                    .map(LedgerOcrLineItemResponse::itemName)
                    .filter(this::isPresent)
                    .toList()
                    .stream()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            addIfPresent(parts, "Items: " + itemSummary);
        }
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

    private MatchedCategory matchCategory(Long userId, EntryType entryType, RemoteParsedResult parsed) {
        if (parsed == null) {
            return new MatchedCategory(null, null);
        }

        List<String> candidates = nonBlankCandidates(
                parsed.categoryDetailName(),
                parsed.categoryGroupName(),
                parsed.categoryText()
        );
        List<CategoryGroup> groups = categoryGroupRepository.findAllByOwnerIdAndEntryTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(userId, entryType);

        for (CategoryGroup group : groups) {
            List<CategoryDetail> details = categoryDetailRepository.findAllByGroupIdOrderByDisplayOrderAscIdAsc(group.getId());
            for (CategoryDetail detail : details) {
                if (matchesAny(detail.getName(), candidates)) {
                    return new MatchedCategory(group, detail);
                }
            }
        }

        for (CategoryGroup group : groups) {
            if (matchesAny(group.getName(), candidates)) {
                return new MatchedCategory(group, null);
            }
        }

        return new MatchedCategory(null, null);
    }

    private PaymentMethod matchPaymentMethod(Long userId, RemoteParsedResult parsed) {
        if (parsed == null || parsed.entryType() == EntryType.INCOME) {
            return null;
        }
        List<String> candidates = nonBlankCandidates(parsed.paymentMethodText());
        return paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(userId).stream()
                .filter((paymentMethod) -> matchesAny(paymentMethod.getName(), candidates))
                .findFirst()
                .orElse(null);
    }

    private List<String> nonBlankCandidates(String... values) {
        List<String> candidates = new ArrayList<>();
        for (String value : values) {
            if (isPresent(value)) {
                candidates.add(value);
            }
        }
        return candidates;
    }

    private boolean matchesAny(String value, List<String> candidates) {
        String normalizedValue = normalizeMatchText(value);
        if (normalizedValue.length() < 2) {
            return false;
        }
        return candidates.stream()
                .map(this::normalizeMatchText)
                .filter((candidate) -> candidate.length() >= 2)
                .anyMatch((candidate) -> normalizedValue.equals(candidate)
                        || normalizedValue.contains(candidate)
                        || candidate.contains(normalizedValue));
    }

    private String normalizeMatchText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-_/().\\[\\]]+", "");
        return normalized.trim();
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

    private record MatchedCategory(CategoryGroup group, CategoryDetail detail) {
    }
}
