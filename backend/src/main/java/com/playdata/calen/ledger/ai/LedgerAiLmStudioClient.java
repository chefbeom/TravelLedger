package com.playdata.calen.ledger.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playdata.calen.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class LedgerAiLmStudioClient {

    private final LedgerAiAnalysisProperties properties;
    private final ObjectMapper objectMapper;

    public LedgerAiRemoteResponse analyze(Object payload) {
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(properties.getConnectTimeout());
            requestFactory.setReadTimeout(properties.getReadTimeout());

            RestClient restClient = RestClient.builder()
                    .baseUrl(properties.getLmStudioBaseUrl())
                    .requestFactory(requestFactory)
                    .build();

            RestClient.RequestBodySpec request = restClient.post()
                    .uri(properties.normalizedLmStudioChatPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);

            if (hasText(properties.getLmStudioApiKey())) {
                request.header("Authorization", "Bearer " + properties.getLmStudioApiKey());
            }

            String responseBody = request.body(buildChatRequest(payload))
                    .retrieve()
                    .body(String.class);

            String content = extractAssistantContent(responseBody);
            String json = extractJsonObject(content);
            LedgerAiRemoteResponse response = objectMapper.readValue(json, LedgerAiRemoteResponse.class);
            return LedgerAiRemoteResponseValidator.requireUsable(response, "LM Studio");

        } catch (BadRequestException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new BadRequestException("LM Studio AI 서버에 연결할 수 없습니다. APP_LEDGER_AI_LMSTUDIO_BASE_URL과 LM Studio 서버 상태를 확인하세요.");
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("LM Studio AI 응답을 JSON 분석 결과로 해석하지 못했습니다. 모델이 JSON only 형식으로 응답하는지 확인하세요.");
        }
    }

    private ObjectNode buildChatRequest(Object payload) throws JsonProcessingException {
        String payloadJson = objectMapper.writeValueAsString(payload);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getModel());
        root.put("temperature", properties.getTemperature());
        root.put("max_tokens", properties.getMaxTokens());
        root.put("stream", false);

        ArrayNode messages = root.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", "You are a Korean household ledger analyst for TravelLedger. Return JSON only. Do not use markdown. Base every statement only on the provided ledger dataset.");
        messages.addObject()
                .put("role", "user")
                .put("content", "Analyze this TravelLedger payload and return exactly the JSON object requested by outputContract.\n\n" + payloadJson);
        return root;
    }

    private String extractAssistantContent(String responseBody) throws JsonProcessingException {
        if (!hasText(responseBody)) {
            throw new BadRequestException("LM Studio AI 서버가 빈 응답을 반환했습니다.");
        }
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode firstChoice = choices.get(0);
            String messageContent = firstChoice.path("message").path("content").asText("");
            if (hasText(messageContent)) {
                return messageContent;
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
        String content = root.path("content").asText("");
        if (hasText(content)) {
            return content;
        }
        if (root.has("report") || root.has("summary")) {
            return responseBody;
        }
        throw new BadRequestException("LM Studio AI 응답에서 분석 JSON 본문을 찾지 못했습니다.");
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
            throw new BadRequestException("LM Studio AI 응답에 JSON 객체가 없습니다.");
        }
        return trimmed.substring(start, end + 1);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}