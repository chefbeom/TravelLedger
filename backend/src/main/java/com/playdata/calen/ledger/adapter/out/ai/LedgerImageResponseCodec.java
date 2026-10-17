package com.playdata.calen.ledger.adapter.out.ai;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.model.LedgerImageData.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Owns provider JSON aliases and existing row/date/type evidence normalization. */
@Component
@RequiredArgsConstructor
public class LedgerImageResponseCodec {
    private static final String YEAR_INFERRED_WARNING = "\uC5F0\uB3C4\uAC00 \uC5C6\uB294 \uB0A0\uC9DC\uB97C \uD604\uC7AC \uC5F0\uB3C4\uB85C \uBCF4\uC815\uD588\uC2B5\uB2C8\uB2E4.";
    private static final String ROW_DATE_CORRECTED_WARNING = "OCR \uD589 \uAE30\uC900\uC73C\uB85C \uAC70\uB798\uC77C\uC744 \uBCF4\uC815\uD588\uC2B5\uB2C8\uB2E4.";
    private static final String ROW_TIME_CLEARED_WARNING = "OCR \uD589\uC5D0 \uC2DC\uAC04 \uADFC\uAC70\uAC00 \uC5C6\uC5B4 \uAC70\uB798 \uC2DC\uAC04\uC744 \uBE44\uC6E0\uC2B5\uB2C8\uB2E4.";
    private static final String ENTRY_TYPE_CORRECTED_WARNING = "\uAD6C\uB9E4/\uACB0\uC81C \uBB38\uB9E5\uC744 \uAE30\uC900\uC73C\uB85C \uAC70\uB798 \uAD6C\uBD84\uC744 \uC9C0\uCD9C\uB85C \uBCF4\uC815\uD588\uC2B5\uB2C8\uB2E4.";
    private static final List<String> DATE_TEXT_FIELDS = List.of(
            "date", "entryDate", "transactionDate", "paymentDate", "orderDate", "purchaseDate", "approvalDate",
            "transactionDateTime", "paymentDateTime", "orderDateTime",
            "dateText", "sourceDateText", "ocrDateText", "rowDateText"
    );
    private static final List<String> TIME_TEXT_FIELDS = List.of(
            "time", "entryTime", "transactionTime", "paymentTime", "orderTime", "purchaseTime", "approvalTime",
            "timeText", "transactionDateTime", "paymentDateTime", "orderDateTime",
            "sourceTimeText", "ocrTimeText", "rowTimeText"
    );


    private final ObjectMapper objectMapper;

    ImageExtraction decode(String responseBody, String fallbackDocumentType, long startedAt) {
        return decode(responseBody, fallbackDocumentType, startedAt, null);
    }

    ImageExtraction decode(String responseBody, String fallbackDocumentType, long startedAt, JsonNode ocrRoot) {
        RemoteAnalyzeResponse response = decodeWire(responseBody, fallbackDocumentType, startedAt, ocrRoot);
        return new ImageExtraction(response.ok(), response.error(), response.documentType(), response.rawText(),
                entry(response.parsed()), response.parsedEntries() == null ? null : response.parsedEntries().stream().map(this::entry).toList(),
                response.timing());
    }

    private ParsedImageEntry entry(RemoteParsedResult value) {
        if (value == null) return null;
        return new ParsedImageEntry(value.entryDate(), value.entryTime(), value.entryType(), value.title(), value.memo(),
                value.amount(), value.currencyCode(), value.foreignAmount(), value.vendor(), value.paymentMethodText(),
                value.categoryGroupName(), value.categoryDetailName(), value.categoryText(),
                value.lineItems() == null ? null : value.lineItems().stream().map(item -> item == null ? null
                        : new ImageLineItem(item.itemName(), item.quantity(), item.unit(), item.price())).toList(),
                value.confidence(), value.warnings());
    }

    JsonNode readAssistantJsonObject(String responseBody) throws JsonProcessingException {
        return objectMapper.readTree(extractJsonObject(extractAssistantContent(responseBody)));
    }


    RemoteAnalyzeResponse decodeWire(String responseBody, String fallbackDocumentType, long startedAt) {
        return decodeWire(responseBody, fallbackDocumentType, startedAt, null);
    }

    RemoteAnalyzeResponse decodeWire(String responseBody, String fallbackDocumentType, long startedAt, JsonNode ocrRoot) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(extractAssistantContent(responseBody)));
            String documentType = firstNonBlank(root.path("documentType").asText(""), normalizeDocumentType(fallbackDocumentType));
            String rawText = firstNonBlank(root.path("rawText").asText(""), ocrRoot != null ? ocrRoot.path("rawText").asText("") : "");
            String resolvedDocumentType = normalizeDocumentType(documentType);
            List<String> rootWarnings = readTextList(root.path("warnings"));
            JsonNode entriesNode = root.path("entries");
            if (!entriesNode.isArray()) {
                entriesNode = root.path("parsedEntries");
            }
            if (!entriesNode.isArray() && root.has("firstEntry")) {
                entriesNode = objectMapper.createArrayNode().add(root.path("firstEntry"));
            }

            List<RemoteParsedResult> entries = entriesNode.isArray()
                    ? objectMapper.convertValue(entriesNode, new TypeReference<List<RemoteParsedResult>>() {})
                    : List.of();
            if (entriesNode.isArray()) {
                entries = normalizeEntryTemporalValues(entriesNode, entries, rawText);
            }
            entries = entries.stream()
                    .map(entry -> mergeRootWarnings(entry, rootWarnings))
                    .toList();
            RemoteParsedResult firstEntry = entries.isEmpty() ? null : entries.get(0);
            if (firstEntry == null && root.has("parsed")) {
                JsonNode parsedNode = root.path("parsed");
                firstEntry = objectMapper.convertValue(parsedNode, RemoteParsedResult.class);
                firstEntry = normalizeEntryTemporalValues(parsedNode, firstEntry, rawText, true);
                firstEntry = mergeRootWarnings(firstEntry, rootWarnings);
                entries = List.of(firstEntry);
            }
            entries = normalizeEntryTypes(entries, resolvedDocumentType, rawText);
            firstEntry = entries.isEmpty() ? null : entries.get(0);
            long llmMs = Math.max(0, Math.round((System.nanoTime() - startedAt) / 1_000_000.0));
            Map<String, Object> timing = Map.of(
                    "llmMs", llmMs,
                    "engine", "lmstudio-vision"
            );
            return new RemoteAnalyzeResponse(true, null, resolvedDocumentType, rawText, firstEntry, entries, timing);
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("AI 이미지 분석 응답을 JSON으로 해석하지 못했습니다. 관리자 화면에서 모델이 JSON만 반환하도록 설정해 주세요.");
        }
    }

    private List<RemoteParsedResult> normalizeEntryTypes(
            List<RemoteParsedResult> entries,
            String documentType,
            String rawText
    ) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .filter(Objects::nonNull)
                .map(entry -> copyWithEntryType(entry, resolveEntryType(entry, documentType, rawText)))
                .toList();
    }

    private EntryType resolveEntryType(RemoteParsedResult entry, String documentType, String rawText) {
        String entryEvidence = normalizeEntryTypeEvidence(String.join(" ",
                safeText(entry.title()),
                safeText(entry.memo()),
                safeText(entry.vendor()),
                safeText(entry.categoryGroupName()),
                safeText(entry.categoryDetailName()),
                safeText(entry.categoryText()),
                entry.lineItems() == null ? "" : entry.lineItems().stream()
                        .filter(Objects::nonNull)
                        .map(RemoteLineItem::itemName)
                        .filter(this::hasText)
                        .reduce("", (left, right) -> left + " " + right)
        ));
        if (containsAny(entryEvidence, List.of(
                "\uAE09\uC5EC", "\uC6D4\uAE09", "\uC785\uAE08 \uBC1B\uC74C", "\uC785\uAE08\uB418\uC5C8", "\uD658\uBD88 \uC644\uB8CC", "\uD658\uAE09",
                "\uBC30\uB2F9\uAE08", "\uC774\uC790 \uC218\uC775", "salary", "income", "deposit received", "credit received", "refund", "cashback"
        ))) {
            return EntryType.INCOME;
        }
        if (containsAny(entryEvidence, List.of(
                "\uAD6C\uB9E4", "\uC8FC\uBB38", "\uACB0\uC81C", "\uC0C1\uD488\uAE08\uC561", "\uBC30\uC1A1\uBE44", "\uC601\uC218\uC99D", "\uCE74\uB4DC \uC2B9\uC778", "\uCCAD\uAD6C", "\uB0A9\uBD80", "\uCD9C\uAE08",
                "payment", "purchase", "order", "paid", "sales slip", "invoice", "charged"
        ))) {
            return EntryType.EXPENSE;
        }

        String documentEvidence = normalizeEntryTypeEvidence(rawText);
        boolean shoppingOrPaymentDocument = containsAny(documentEvidence, List.of(
                "\uC8FC\uBB38 \uC815\uBCF4", "\uC8FC\uBB38\uC77C\uC790", "\uC8FC\uBB38 \uC0C1\uD488", "\uC0C1\uD488\uAE08\uC561", "\uAD6C\uB9E4\uD655\uC815", "\uACB0\uC81C\uC644\uB8CC", "\uACB0\uC81C \uAE08\uC561",
                "order info", "order date", "product amount", "purchase confirmed", "payment completed", "sales slip"
        ));
        String normalizedDocumentType = normalizeDocumentType(documentType);
        if (shoppingOrPaymentDocument
                || "RECEIPT".equals(normalizedDocumentType)
                || "PAYMENT_CAPTURE".equals(normalizedDocumentType)) {
            return EntryType.EXPENSE;
        }
        return entry.entryType() == EntryType.INCOME ? EntryType.INCOME : EntryType.EXPENSE;
    }

    private RemoteParsedResult copyWithEntryType(RemoteParsedResult entry, EntryType entryType) {
        List<String> warnings = new ArrayList<>();
        if (entry.warnings() != null) {
            warnings.addAll(entry.warnings());
        }
        if (entry.entryType() == EntryType.INCOME
                && entryType == EntryType.EXPENSE
                && !warnings.contains(ENTRY_TYPE_CORRECTED_WARNING)) {
            warnings.add(ENTRY_TYPE_CORRECTED_WARNING);
        }
        return new RemoteParsedResult(
                entry.entryDate(), entry.entryTime(), entryType, entry.title(), entry.memo(), entry.amount(),
                entry.currencyCode(),
                entry.foreignAmount(),
                entry.vendor(), entry.paymentMethodText(), entry.categoryGroupName(), entry.categoryDetailName(),
                entry.categoryText(), entry.lineItems(), entry.confidence(), warnings
        );
    }

    private String normalizeEntryTypeEvidence(String value) {
        return safeText(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private boolean containsAny(String value, List<String> candidates) {
        return hasText(value) && candidates.stream().anyMatch(value::contains);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private List<RemoteParsedResult> normalizeEntryTemporalValues(JsonNode entriesNode, List<RemoteParsedResult> entries, String rawText) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        if (entriesNode == null || !entriesNode.isArray()) {
            return entries;
        }
        List<RemoteParsedResult> normalized = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            JsonNode entryNode = index < entriesNode.size() ? entriesNode.get(index) : null;
            normalized.add(normalizeEntryTemporalValues(entryNode, entries.get(index), rawText, entries.size() == 1));
        }
        return normalized;
    }

    private RemoteParsedResult normalizeEntryTemporalValues(JsonNode entryNode, RemoteParsedResult entry, String rawText, boolean allowFullRawTextFallback) {
        if (entry == null || entryNode == null || entryNode.isMissingNode() || entryNode.isNull()) {
            return entry;
        }
        List<String> dateTexts = new ArrayList<>(collectTextFields(entryNode, DATE_TEXT_FIELDS));
        List<String> timeTexts = new ArrayList<>(collectTextFields(entryNode, TIME_TEXT_FIELDS));
        List<String> rowTemporalTexts = extractRawTextWindowsForEntry(entry, rawText);
        LocalDate rowDate = firstParsedDate(rowTemporalTexts, List.of());
        LocalTime rowTime = firstParsedTime(rowTemporalTexts, List.of());
        addFallbackTemporalTexts(dateTexts, timeTexts, entry, rawText, allowFullRawTextFallback);

        LocalDate parsedDate = firstParsedDate(dateTexts, timeTexts);
        LocalTime parsedTime = firstParsedTime(timeTexts, dateTexts);
        LocalDate entryDate = entry.entryDate();
        LocalTime entryTime = entry.entryTime();
        boolean correctedByRowDate = false;
        boolean clearedByRowTime = false;

        if (rowDate != null && (entryDate == null || (!allowFullRawTextFallback && !Objects.equals(entryDate, rowDate)))) {
            correctedByRowDate = entryDate != null && !Objects.equals(entryDate, rowDate);
            entryDate = rowDate;
        } else if (entryDate == null) {
            entryDate = parsedDate;
        }

        if (rowTime != null && (entryTime == null || (!allowFullRawTextFallback && !Objects.equals(entryTime, rowTime)))) {
            entryTime = rowTime;
        } else if (entryTime == null) {
            entryTime = parsedTime;
        } else if (!allowFullRawTextFallback && rowDate != null && rowTime == null && !rowTemporalTexts.isEmpty()) {
            entryTime = null;
            clearedByRowTime = true;
        }

        boolean inferredYear = entryDate != null && hasYearlessDateText(dateTexts, timeTexts, rowTemporalTexts);
        if (Objects.equals(entryDate, entry.entryDate())
                && Objects.equals(entryTime, entry.entryTime())
                && !inferredYear
                && !correctedByRowDate
                && !clearedByRowTime) {
            return entry;
        }
        List<String> warnings = new ArrayList<>();
        if (entry.warnings() != null) {
            warnings.addAll(entry.warnings());
        }
        if (inferredYear && !warnings.contains(YEAR_INFERRED_WARNING)) {
            warnings.add(YEAR_INFERRED_WARNING);
        }
        if (correctedByRowDate && !warnings.contains(ROW_DATE_CORRECTED_WARNING)) {
            warnings.add(ROW_DATE_CORRECTED_WARNING);
        }
        if (clearedByRowTime && !warnings.contains(ROW_TIME_CLEARED_WARNING)) {
            warnings.add(ROW_TIME_CLEARED_WARNING);
        }
        return copyWithTemporalAndWarnings(entry, entryDate, entryTime, warnings);
    }

    private List<String> collectTextFields(JsonNode node, List<String> fieldNames) {
        if (node == null || fieldNames == null || fieldNames.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String fieldName : fieldNames) {
            JsonNode valueNode = node.get(fieldName);
            if (valueNode == null || valueNode.isNull() || valueNode.isMissingNode()) {
                continue;
            }
            String value = valueNode.isTextual() ? valueNode.asText() : valueNode.toString();
            if (hasText(value)) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private void addFallbackTemporalTexts(
            List<String> dateTexts,
            List<String> timeTexts,
            RemoteParsedResult entry,
            String rawText,
            boolean allowFullRawTextFallback
    ) {
        List<String> fallbackTexts = new ArrayList<>();
        if (entry != null) {
            addTextIfPresent(fallbackTexts, entry.title());
            addTextIfPresent(fallbackTexts, entry.memo());
            addTextIfPresent(fallbackTexts, entry.vendor());
            addTextIfPresent(fallbackTexts, entry.categoryText());
            if (entry.lineItems() != null) {
                entry.lineItems().forEach(item -> addTextIfPresent(fallbackTexts, item == null ? null : item.itemName()));
            }
        }
        fallbackTexts.addAll(extractRawTextWindowsForEntry(entry, rawText));
        if (allowFullRawTextFallback) {
            addTextIfPresent(fallbackTexts, rawText);
        }
        for (String text : fallbackTexts) {
            if (!dateTexts.contains(text)) {
                dateTexts.add(text);
            }
            if (!timeTexts.contains(text)) {
                timeTexts.add(text);
            }
        }
    }

    private List<String> extractRawTextWindowsForEntry(RemoteParsedResult entry, String rawText) {
        if (entry == null || !hasText(rawText)) {
            return List.of();
        }
        List<String> textWindows = rawTextWindows(rawText, temporalTextNeedles(entry));
        if (!textWindows.isEmpty()) {
            return textWindows;
        }
        return rawTextWindows(rawText, temporalAmountNeedles(entry));
    }

    private List<String> temporalTextNeedles(RemoteParsedResult entry) {
        List<String> needles = new ArrayList<>();
        addTemporalNeedles(needles, entry.title());
        if (entry.lineItems() != null) {
            entry.lineItems().forEach(item -> addTemporalNeedles(needles, item == null ? null : item.itemName()));
        }
        addTemporalNeedles(needles, entry.memo());
        needles.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return needles;
    }

    private List<String> temporalAmountNeedles(RemoteParsedResult entry) {
        if (entry.amount() == null) {
            return List.of();
        }
        long amount = Math.abs(entry.amount().longValue());
        if (amount <= 0) {
            return List.of();
        }
        List<String> needles = new ArrayList<>();
        addDistinctText(needles, String.format(Locale.ROOT, "%,d", amount));
        addDistinctText(needles, Long.toString(amount));
        return needles;
    }

    private void addTemporalNeedles(List<String> needles, String value) {
        if (!hasText(value)) {
            return;
        }
        String cleaned = value.trim();
        if (cleaned.length() >= 4 && cleaned.length() <= 90 && !isGenericTemporalNeedle(cleaned)) {
            addDistinctText(needles, cleaned);
        }
        for (String token : cleaned.split("[^0-9A-Za-z가-힣_]+")) {
            if (token.length() >= 4 && !token.matches("\\d+") && !isGenericTemporalNeedle(token)) {
                addDistinctText(needles, token);
            }
        }
    }

    private boolean isGenericTemporalNeedle(String value) {
        if (!hasText(value)) {
            return true;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z가-힣]", "");
        return normalized.isBlank()
                || List.of(
                "결제완료",
                "구매확정",
                "구매확정완료",
                "상세보기",
                "배송조회",
                "리뷰쓰기",
                "다시담기",
                "상품금액",
                "주문일자",
                "주문상품정보",
                "주문상태",
                "네이버페이"
        ).contains(normalized);
    }

    private List<String> rawTextWindows(String rawText, List<String> needles) {
        if (!hasText(rawText) || needles == null || needles.isEmpty()) {
            return List.of();
        }
        String normalizedRawText = rawText.toLowerCase(Locale.ROOT);
        List<String> windows = new ArrayList<>();
        for (String needle : needles) {
            if (!hasText(needle)) {
                continue;
            }
            String normalizedNeedle = needle.toLowerCase(Locale.ROOT);
            int index = normalizedRawText.indexOf(normalizedNeedle);
            if (index < 0) {
                continue;
            }
            int lineStart = previousLineStart(rawText, index);
            int lineEnd = nextLineEnd(rawText, index);
            int start;
            int end;
            if (lineStart >= 0 || lineEnd >= 0) {
                int currentLineStart = lineStart >= 0 ? lineStart : 0;
                int currentLineEnd = lineEnd >= 0 ? lineEnd : rawText.length();
                addDistinctText(windows, rawText.substring(currentLineStart, currentLineEnd));
                start = previousLinesStart(rawText, currentLineStart, 3);
                end = nextLinesEnd(rawText, currentLineEnd, 4);
            } else {
                start = Math.max(0, index - 180);
                end = Math.min(rawText.length(), index + needle.length() + 180);
            }
            if (start < end) {
                addDistinctText(windows, rawText.substring(start, end));
            }
            if (windows.size() >= 3) {
                break;
            }
        }
        return windows;
    }

    private int previousLinesStart(String text, int start, int lineCount) {
        int result = Math.max(0, Math.min(start, text.length()));
        for (int i = 0; i < lineCount; i++) {
            int searchFrom = Math.max(0, result - 2);
            int lineFeed = text.lastIndexOf('\n', searchFrom);
            int carriageReturn = text.lastIndexOf('\r', searchFrom);
            int lineBreak = Math.max(lineFeed, carriageReturn);
            if (lineBreak < 0) {
                return 0;
            }
            result = lineBreak + 1;
        }
        return result;
    }

    private int nextLinesEnd(String text, int end, int lineCount) {
        int result = Math.max(0, Math.min(end, text.length()));
        for (int i = 0; i < lineCount; i++) {
            int searchFrom = Math.min(text.length(), result + 1);
            int lineFeed = text.indexOf('\n', searchFrom);
            int carriageReturn = text.indexOf('\r', searchFrom);
            int lineBreak;
            if (lineFeed < 0) {
                lineBreak = carriageReturn;
            } else if (carriageReturn < 0) {
                lineBreak = lineFeed;
            } else {
                lineBreak = Math.min(lineFeed, carriageReturn);
            }
            if (lineBreak < 0) {
                return text.length();
            }
            result = lineBreak;
        }
        return result;
    }

    private int previousLineStart(String text, int index) {
        int lineFeed = text.lastIndexOf('\n', Math.max(0, index));
        int carriageReturn = text.lastIndexOf('\r', Math.max(0, index));
        int lineBreak = Math.max(lineFeed, carriageReturn);
        return lineBreak >= 0 ? lineBreak + 1 : -1;
    }

    private int nextLineEnd(String text, int index) {
        int lineFeed = text.indexOf('\n', Math.max(0, index));
        int carriageReturn = text.indexOf('\r', Math.max(0, index));
        if (lineFeed < 0) {
            return carriageReturn;
        }
        if (carriageReturn < 0) {
            return lineFeed;
        }
        return Math.min(lineFeed, carriageReturn);
    }
    private void addDistinctText(List<String> texts, String value) {
        if (hasText(value) && !texts.contains(value.trim())) {
            texts.add(value.trim());
        }
    }

    private void addTextIfPresent(List<String> texts, String value) {
        if (hasText(value)) {
            texts.add(value.trim());
        }
    }
    private LocalDate firstParsedDate(List<String> primaryTexts, List<String> fallbackTexts) {
        for (String value : concatTextCandidates(primaryTexts, fallbackTexts)) {
            LocalDate parsed = FlexibleLocalDateDeserializer.parseDate(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private LocalTime firstParsedTime(List<String> primaryTexts, List<String> fallbackTexts) {
        for (String value : concatTextCandidates(primaryTexts, fallbackTexts)) {
            LocalTime parsed = FlexibleLocalTimeDeserializer.parseTime(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private List<String> concatTextCandidates(List<String> primaryTexts, List<String> fallbackTexts) {
        List<String> values = new ArrayList<>();
        if (primaryTexts != null) {
            values.addAll(primaryTexts);
        }
        if (fallbackTexts != null) {
            values.addAll(fallbackTexts);
        }
        return values;
    }

    private boolean hasYearlessDateText(List<String> primaryTexts, List<String> fallbackTexts) {
        return hasYearlessDateText(primaryTexts, fallbackTexts, List.of());
    }

    private boolean hasYearlessDateText(List<String> primaryTexts, List<String> fallbackTexts, List<String> rowTemporalTexts) {
        List<String> candidates = new ArrayList<>(concatTextCandidates(primaryTexts, fallbackTexts));
        if (rowTemporalTexts != null) {
            candidates.addAll(rowTemporalTexts);
        }
        for (String value : candidates) {
            if (FlexibleLocalDateDeserializer.hasYearlessDate(value)) {
                return true;
            }
        }
        return false;
    }
    private RemoteParsedResult copyWithTemporalAndWarnings(RemoteParsedResult entry, LocalDate entryDate, LocalTime entryTime, List<String> warnings) {
        RemoteParsedResult normalized = new RemoteParsedResult(
                entryDate,
                entryTime,
                entry.entryType(),
                entry.title(),
                entry.memo(),
                entry.amount(),
                entry.currencyCode(),
                entry.foreignAmount(),
                entry.vendor(),
                entry.paymentMethodText(),
                entry.categoryGroupName(),
                entry.categoryDetailName(),
                entry.categoryText(),
                entry.lineItems(),
                entry.confidence(),
                warnings
        );
        return copyWithWarnings(normalized, warnings);
    }

    private RemoteParsedResult copyWithWarnings(RemoteParsedResult entry, List<String> warnings) {
        return new RemoteParsedResult(
                entry.entryDate(),
                entry.entryTime(),
                entry.entryType(),
                entry.title(),
                entry.memo(),
                entry.amount(),
                entry.currencyCode(),
                entry.foreignAmount(),
                entry.vendor(),
                entry.paymentMethodText(),
                entry.categoryGroupName(),
                entry.categoryDetailName(),
                entry.categoryText(),
                entry.lineItems(),
                entry.confidence(),
                sanitizeTemporalWarnings(entry, warnings)
        );
    }

    private List<String> sanitizeTemporalWarnings(RemoteParsedResult entry, List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>();
        for (String warning : warnings) {
            if (!hasText(warning) || isResolvedTemporalWarning(entry, warning)) {
                continue;
            }
            if (!sanitized.contains(warning)) {
                sanitized.add(warning);
            }
        }
        return sanitized;
    }

    private boolean isResolvedTemporalWarning(RemoteParsedResult entry, String warning) {
        if (entry == null || !hasText(warning)) {
            return false;
        }
        String normalized = warning.toLowerCase(Locale.ROOT);
        boolean dateResolved = entry.entryDate() != null;
        boolean timeResolved = entry.entryTime() != null;
        if (timeResolved && normalized.contains("null") && warning.contains("시간")) {
            return true;
        }
        if (dateResolved && normalized.contains("null") && warning.contains("날짜")) {
            return true;
        }
        return dateResolved && timeResolved
                && warning.contains("날짜")
                && warning.contains("불분명")
                && warning.contains("시간");
    }
    private RemoteParsedResult mergeRootWarnings(RemoteParsedResult entry, List<String> rootWarnings) {
        if (entry == null || rootWarnings.isEmpty()) {
            return entry;
        }
        List<String> warnings = new ArrayList<>();
        if (entry.warnings() != null) {
            warnings.addAll(entry.warnings());
        }
        for (String warning : rootWarnings) {
            if (hasText(warning) && !warnings.contains(warning)) {
                warnings.add(warning);
            }
        }
        return copyWithWarnings(entry, warnings);
    }

    private String extractAssistantContent(String responseBody) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isTextual()) {
                return content.asText("");
            }
            if (content.isArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonNode item : content) {
                    String text = item.path("text").asText("");
                    if (hasText(text)) {
                        builder.append(text).append('\n');
                    }
                }
                return builder.toString();
            }
        }
        JsonNode messageContent = root.path("message").path("content");
        if (messageContent.isTextual() && hasText(messageContent.asText(""))) {
            return messageContent.asText("");
        }
        return responseBody;
    }

    private String extractJsonObject(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("(?s)^```[a-zA-Z0-9_-]*\\s*", "");
            normalized = normalized.replaceFirst("(?s)\\s*```$", "").trim();
        }
        int start = normalized.indexOf('{');
        if (start < 0) {
            return normalized;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth += 1;
            } else if (current == '}') {
                depth -= 1;
                if (depth == 0) {
                    return normalized.substring(start, i + 1);
                }
            }
        }
        int end = normalized.lastIndexOf('}');
        return end > start ? normalized.substring(start, end + 1) : normalized;
    }


    private List<String> readTextList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (hasText(value)) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private String normalizeDocumentType(String documentType) {
        if (!hasText(documentType)) {
            return "AUTO";
        }
        String normalized = documentType.trim().toUpperCase().replace('-', '_');
        return switch (normalized) {
            case "RECEIPT", "PAYMENT_CAPTURE", "AUTO" -> normalized;
            default -> "AUTO";
        };
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }


    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    static class FlexibleLocalDateDeserializer extends JsonDeserializer<LocalDate> {
        private static final Pattern FULL_DATE_PATTERN = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})\\s*(?:[-./]|\\uB144)\\s*(\\d{1,2})\\s*(?:[-./]|\\uC6D4)\\s*(\\d{1,2})(?:\\s*\\uC77C)?");
        private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2})\\s*(?:[-./]|\\uC6D4)\\s*(\\d{1,2})(?:\\s*\\uC77C)?(?!\\d)");
        private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

        @Override
        public LocalDate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_NULL) {
                return null;
            }
            return parseDate(parser.getValueAsString());
        }

        static LocalDate parseDate(String value) {
            if (!hasTextStatic(value) || "null".equalsIgnoreCase(value.trim())) {
                return null;
            }
            String normalized = value.trim();
            try {
                return LocalDate.parse(normalized, ISO_DATE);
            } catch (DateTimeParseException ignored) {
                Matcher matcher = FULL_DATE_PATTERN.matcher(normalized);
                if (matcher.find()) {
                    return safeDate(
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2)),
                            Integer.parseInt(matcher.group(3))
                    );
                }
                matcher = MONTH_DAY_PATTERN.matcher(normalized);
                if (matcher.find()) {
                    return safeDate(
                            LocalDate.now().getYear(),
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2))
                    );
                }
                return null;
            }
        }

        static boolean hasYearlessDate(String value) {
            if (!hasTextStatic(value)) {
                return false;
            }
            return !FULL_DATE_PATTERN.matcher(value).find() && MONTH_DAY_PATTERN.matcher(value).find();
        }

        private static LocalDate safeDate(int year, int month, int day) {
            try {
                return LocalDate.of(year, month, day);
            } catch (DateTimeException exception) {
                return null;
            }
        }
    }

    static class FlexibleLocalTimeDeserializer extends JsonDeserializer<LocalTime> {
        private static final Pattern TIME_PATTERN = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])\\s*(?::|\\uC2DC)\\s*([0-5]\\d)(?:\\s*(?::|\\uBD84)\\s*([0-5]\\d))?");
        private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("H:mm");
        private static final DateTimeFormatter LONG_TIME = DateTimeFormatter.ofPattern("H:mm:ss");

        @Override
        public LocalTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_NULL) {
                return null;
            }
            return parseTime(parser.getValueAsString());
        }

        static LocalTime parseTime(String value) {
            if (!hasTextStatic(value) || "null".equalsIgnoreCase(value.trim())) {
                return null;
            }
            String normalized = value.trim();
            for (DateTimeFormatter formatter : List.of(SHORT_TIME, LONG_TIME)) {
                try {
                    return LocalTime.parse(normalized, formatter).withSecond(0).withNano(0);
                } catch (DateTimeParseException ignored) {
                    // continue with text extraction below
                }
            }
            Matcher matcher = TIME_PATTERN.matcher(normalized);
            if (matcher.find()) {
                int hour = Integer.parseInt(matcher.group(1));
                int minute = Integer.parseInt(matcher.group(2));
                return LocalTime.of(hour, minute);
            }
            return null;
        }
    }

    private static boolean hasTextStatic(String value) {
        return value != null && !value.isBlank();
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteAnalyzeResponse(
            boolean ok,
            String error,
            String documentType,
            String rawText,
            @JsonAlias("firstEntry")
            RemoteParsedResult parsed,
            @JsonAlias("entries")
            List<RemoteParsedResult> parsedEntries,
            @JsonAlias("ocrTiming")
            Map<String, Object> timing
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteParsedResult(
            @JsonAlias({"date", "entryDate", "transactionDate", "paymentDate", "orderDate", "purchaseDate", "approvalDate", "transactionDateTime", "paymentDateTime", "orderDateTime", "dateText", "sourceDateText", "ocrDateText", "rowDateText"})
            @JsonDeserialize(using = FlexibleLocalDateDeserializer.class)
            LocalDate entryDate,
            @JsonAlias({"time", "entryTime", "transactionTime", "paymentTime", "orderTime", "purchaseTime", "approvalTime", "timeText", "transactionDateTime", "paymentDateTime", "orderDateTime", "sourceTimeText", "ocrTimeText", "rowTimeText"})
            @JsonFormat(pattern = "HH:mm")
            @JsonDeserialize(using = FlexibleLocalTimeDeserializer.class)
            LocalTime entryTime,
            @JsonAlias({"type", "entryType"})
            EntryType entryType,
            @JsonAlias({"transactionTitle", "storeName", "store"})
            String title,
            String memo,
            @JsonAlias({"totalAmount", "price"})
            BigDecimal amount,
            String currencyCode,
            BigDecimal foreignAmount,
            @JsonAlias({"storeName", "store"})
            String vendor,
            String paymentMethodText,
            String categoryGroupName,
            String categoryDetailName,
            String categoryText,
            @JsonAlias("items")
            List<RemoteLineItem> lineItems,
            Double confidence,
            List<String> warnings
    ) {
        public RemoteParsedResult(
                LocalDate entryDate,
                LocalTime entryTime,
                EntryType entryType,
                String title,
                String memo,
                BigDecimal amount,
                String vendor,
                String paymentMethodText,
                String categoryGroupName,
                String categoryDetailName,
                String categoryText,
                List<RemoteLineItem> lineItems,
                Double confidence,
                List<String> warnings
        ) {
            this(entryDate, entryTime, entryType, title, memo, amount, null, null,
                    vendor, paymentMethodText, categoryGroupName, categoryDetailName,
                    categoryText, lineItems, confidence, warnings);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteLineItem(
            @JsonAlias({"name", "title", "item", "itemName", "product", "productName", "service", "serviceName", "description"})
            String itemName,
            BigDecimal quantity,
            String unit,
            BigDecimal price
    ) {
    }
}
