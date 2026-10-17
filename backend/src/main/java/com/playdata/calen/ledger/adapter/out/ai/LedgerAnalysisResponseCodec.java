package com.playdata.calen.ledger.adapter.out.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.ai.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Provider JSON aliases/envelopes and the existing safe plain-text fallback; not business orchestration. */
@Component
@RequiredArgsConstructor
public class LedgerAnalysisResponseCodec {
    private final ObjectMapper objectMapper;
    private final LedgerAiAnalysisProperties properties;

    Result fromWire(LedgerAnalysisWireResponse value) {
        return value == null ? null : new Result(value.ok(),value.error(),value.summary(),value.highlights(),value.warnings(),value.risks(),value.recommendations(),
                value.categoryInsights(),value.paymentInsights(),value.trendInsights(),value.unusualSpendingInsights(),value.fixedCostInsights(),
                value.nextPeriodForecast(),value.habitAssessment(),value.report());
    }

    String extractAssistantContent(String responseBody) throws JsonProcessingException {
        if (!hasText(responseBody)) {
            throw new BadRequestException(providerLabel() + " AI server returned an empty response.");
        }
        JsonNode root = objectMapper.readTree(responseBody);
        if (root.isTextual() && hasText(root.asText())) {
            return root.asText();
        }

        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode firstChoice = choices.get(0);
            String messageContent = firstChoice.path("message").path("content").asText("");
            if (hasText(messageContent)) {
                return messageContent;
            }
            String deltaContent = firstChoice.path("delta").path("content").asText("");
            if (hasText(deltaContent)) {
                return deltaContent;
            }
            String textContent = firstChoice.path("text").asText("");
            if (hasText(textContent)) {
                return textContent;
            }
        }

        String messageContent = root.path("message").path("content").asText("");
        if (hasText(messageContent)) {
            return messageContent;
        }

        JsonNode data = root.path("data");
        String dataContent = data.path("content").asText("");
        if (hasText(dataContent)) {
            return dataContent;
        }
        String dataResponse = data.path("response").asText("");
        if (hasText(dataResponse)) {
            return dataResponse;
        }

        String[] directTextFields = {"content", "response", "result", "text", "output"};
        for (String field : directTextFields) {
            String value = root.path(field).asText("");
            if (hasText(value)) {
                return value;
            }
        }

        if (root.has("report") || root.has("summary") || root.has("highlights") || root.has("recommendations")) {
            return responseBody;
        }
        throw new BadRequestException(providerLabel() + " AI response could not be parsed as JSON analysis. Check that the model returns JSON only.");
    }

    Result parseAssistantContent(String content) throws JsonProcessingException {
        try {
            return readRemoteResponse(extractJsonObject(content));
        } catch (BadRequestException | JsonProcessingException exception) {
            Result fallback = fallbackPlainTextResponse(content);
            if (fallback != null) {
                return fallback;
            }
            throw exception;
        }
    }

    private Result readRemoteResponse(String json) throws JsonProcessingException {
        try {
            return fromWire(objectMapper.readValue(json, LedgerAnalysisWireResponse.class));
        } catch (JsonProcessingException exception) {
            String relaxedJson = relaxJson(json);
            if (!relaxedJson.equals(json)) {
                return fromWire(objectMapper.readValue(relaxedJson, LedgerAnalysisWireResponse.class));
            }
            throw exception;
        }
    }

    private String relaxJson(String json) {
        String trimmed = json == null ? "" : json.trim();
        return trimmed.replaceAll(",\\s*([}\\]])", "$1");
    }

    private Result fallbackPlainTextResponse(String content) {
        String fallbackText = normalizePlainTextResponse(content);
        if (!hasText(fallbackText)) {
            return null;
        }
        return new Result(
                true,
                null,
                "LM Studio 응답이 구조화 JSON 형식을 지키지 않아 기본 계산 기반 분석으로 보완했습니다.",
                java.util.List.of("AI 응답 형식이 맞지 않아 핵심 지표는 기본 계산 결과로 우선 표시합니다."),
                java.util.List.of("일부 분석 항목은 AI 전문 응답 대신 가계부 데이터 계산 결과로 대체되었습니다."),
                java.util.List.of(),
                java.util.List.of("AI 응답을 다시 시도하거나 관리자 화면에서 모델과 JSON 응답 설정을 확인하세요."),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                "",
                "",
                null
        );
    }

    private String normalizePlainTextResponse(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int firstNewLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewLine >= 0 && lastFence > firstNewLine) {
                text = text.substring(firstNewLine + 1, lastFence).trim();
            }
        }
        return text;
    }

    private String limitText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
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
            throw new BadRequestException(providerLabel() + " AI response could not be parsed as JSON analysis. Check that the model returns JSON only.");
        }
        return trimmed.substring(start, end + 1);
    }


    private String providerLabel() {
        return properties.featureConfig(LedgerAiFeature.LEDGER_ANALYSIS).providerLabel();
    }


    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
