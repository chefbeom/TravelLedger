package com.playdata.calen.ledger.ai;

import com.playdata.calen.ledger.dto.LedgerAiAnalysisStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LedgerAiAnalysisStatusService {

    private final LedgerAiAnalysisProperties properties;

    public LedgerAiAnalysisStatusResponse getStatus() {
        return new LedgerAiAnalysisStatusResponse(
                properties.isEnabled(),
                properties.isConfigured(),
                properties.getProvider(),
                properties.isWorkflowConfigured(),
                properties.isApiKeyConfigured(),
                properties.isLmStudioConfigured(),
                properties.getModel(),
                properties.statusMessage()
        );
    }
}