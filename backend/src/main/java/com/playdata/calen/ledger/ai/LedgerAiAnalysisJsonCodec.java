package com.playdata.calen.ledger.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LedgerAiAnalysisJsonCodec {

    private final ObjectMapper objectMapper;

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize AI analysis payload.", exception);
        }
    }

    public LedgerAiAnalysisResponse readResult(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, LedgerAiAnalysisResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to read AI analysis result.", exception);
        }
    }
}