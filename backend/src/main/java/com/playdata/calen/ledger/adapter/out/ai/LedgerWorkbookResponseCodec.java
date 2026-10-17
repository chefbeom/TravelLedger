package com.playdata.calen.ledger.adapter.out.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LedgerWorkbookResponseCodec {
    private final ObjectMapper objectMapper;
    public Extraction decode(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(extractAssistantContent(responseBody)));
            JsonNode rows = root.path("rows");
            if (!rows.isArray()) rows = root.path("data").path("rows");
            if (!rows.isArray()) throw new BadRequestException("AI Excel 가져오기 응답에 rows 배열이 없습니다.");
            List<Candidate> candidates = new ArrayList<>();
            for (JsonNode row : rows) candidates.add(candidate(row));
            return new Extraction(candidates,readNotes(root.path("notes")));
        } catch (JsonProcessingException failure) {
            throw new BadRequestException("AI Excel 가져오기 응답이 JSON 형식이 아닙니다. 모델의 JSON 응답 설정을 확인해 주세요.");
        }
    }
    private Candidate candidate(JsonNode row) {
        JsonNode amountNode = row.path("amount");
        Amount amount = amountNode.isMissingNode() || amountNode.isNull() ? null :
                amountNode.isNumber() ? new Amount(amountNode.decimalValue(),null) : new Amount(null,amountNode.asText(""));
        return new Candidate(text(row,"sourceId"),text(row,"sourceSheetName"),positiveInt(row.path("sourceRowNumber")),
                text(row,"entryDate"),text(row,"entryTime"),text(row,"title"),text(row,"memo"),amount,text(row,"entryType"),
                text(row,"paymentMethodName"),text(row,"categoryGroupName"),text(row,"categoryDetailName"),readNotes(row.path("issues")));
    }

    private String extractAssistantContent(String responseBody) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(responseBody);
        if (root.has("rows")) {
            return responseBody;
        }
        String content = contentText(root.path("message").path("content"));
        if (hasText(content)) {
            return content;
        }
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            content = contentText(choices.get(0).path("message").path("content"));
            if (hasText(content)) {
                return content;
            }
            content = contentText(choices.get(0).path("text"));
            if (hasText(content)) {
                return content;
            }
        }
        content = contentText(root.path("content"));
        if (hasText(content)) {
            return content;
        }
        content = contentText(root.path("response"));
        if (hasText(content)) {
            return content;
        }
        return responseBody;
    }

    private String contentText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (!node.isArray()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (JsonNode item : node) {
            String value = item.isTextual() ? item.asText("") : item.path("text").asText("");
            if (hasText(value)) {
                result.append(value);
            }
        }
        return result.toString();
    }
    private String extractJsonObject(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewLine = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewLine >= 0 && lastFence > firstNewLine) {
                trimmed = trimmed.substring(firstNewLine + 1, lastFence).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new BadRequestException("AI Excel 가져오기 응답에서 JSON 객체를 찾지 못했습니다.");
        }
        return trimmed.substring(start, end + 1);
    }

    private List<String> readNotes(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> notes = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) notes.add(value);
        }
        return notes;
    }

    private String text(JsonNode node, String field) {
        return Optional.ofNullable(node.path(field).asText(""))
                .map(String::trim)
                .orElse("");
    }

    private int positiveInt(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return -1;
        }
        if (node.canConvertToInt()) {
            int value = node.asInt();
            return value > 0 ? value : -1;
        }
        try {
            int value = Integer.parseInt(node.asText("").trim());
            return value > 0 ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
