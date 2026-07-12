package com.playdata.calen.ledger.ai;

import com.playdata.calen.ledger.dto.LedgerAiAnalysisStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LedgerAiAnalysisStatusService {

    private final LedgerAiAnalysisProperties properties;

    public LedgerAiAnalysisStatusResponse getStatus() {
        LedgerAiFeatureConfig config = properties.featureConfig(LedgerAiFeature.LEDGER_ANALYSIS);
        boolean configured = properties.isFeatureConfigured(LedgerAiFeature.LEDGER_ANALYSIS);
        return new LedgerAiAnalysisStatusResponse(
                properties.isEnabled(),
                configured,
                config.provider().name().toLowerCase(java.util.Locale.ROOT),
                false,
                config.apiKey() != null && !config.apiKey().isBlank(),
                config.provider() == LedgerAiProvider.LMSTUDIO && configured,
                config.provider() == LedgerAiProvider.OPENAI && configured,
                config.model(),
                properties.featureStatusMessage(LedgerAiFeature.LEDGER_ANALYSIS)
        );
    }
}
