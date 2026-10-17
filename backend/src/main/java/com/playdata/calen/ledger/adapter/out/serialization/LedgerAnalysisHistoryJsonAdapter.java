package com.playdata.calen.ledger.adapter.out.serialization;

import com.playdata.calen.ledger.adapter.support.LedgerAnalysisHistoryMapper;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisJsonCodec;
import com.playdata.calen.ledger.application.port.out.LedgerAnalysisHistoryCodec;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.AnalysisResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LedgerAnalysisHistoryJsonAdapter implements LedgerAnalysisHistoryCodec {
    private final LedgerAiAnalysisJsonCodec codec;
    @Override public AnalysisResult read(String value) { return LedgerAnalysisHistoryMapper.facts(codec.readResult(value)); }
}
