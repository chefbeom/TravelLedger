package com.playdata.calen.ledger.adapter.out.ai;

import com.playdata.calen.ledger.ai.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Request;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Result;
import com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class LedgerAnalysisProviderAdapter implements LedgerSpendingAnalysisPort {

    private final LedgerAiAnalysisProperties properties;
    private final LedgerAiN8nClient n8nClient;
    private final LedgerAiLmStudioClient lmStudioClient;

    @Override
    public Result analyze(Request payload) {
        LedgerAiProvider provider = properties.featureConfig(LedgerAiFeature.LEDGER_ANALYSIS).provider();
        if (provider == LedgerAiProvider.LMSTUDIO || provider == LedgerAiProvider.OPENAI || provider == LedgerAiProvider.OLLAMA) {
            return lmStudioClient.analyze(payload);
        }
        return n8nClient.analyze(payload);
    }
}
