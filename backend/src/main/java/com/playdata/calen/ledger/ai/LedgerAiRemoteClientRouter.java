package com.playdata.calen.ledger.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LedgerAiRemoteClientRouter implements LedgerAiRemoteClient {

    private final LedgerAiAnalysisProperties properties;
    private final LedgerAiN8nClient n8nClient;
    private final LedgerAiLmStudioClient lmStudioClient;

    @Override
    public LedgerAiRemoteResponse analyze(Object payload) {
        LedgerAiProvider provider = properties.featureConfig(LedgerAiFeature.LEDGER_ANALYSIS).provider();
        if (provider == LedgerAiProvider.LMSTUDIO || provider == LedgerAiProvider.OPENAI || provider == LedgerAiProvider.OLLAMA) {
            return lmStudioClient.analyze(payload);
        }
        return n8nClient.analyze(payload);
    }
}
