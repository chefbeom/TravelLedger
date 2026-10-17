package com.playdata.calen.ledger.adapter.in.ai;

import com.playdata.calen.ledger.application.port.in.ManageLedgerAnalysisHistoryUseCase;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.application.service.ManageLedgerAnalysisHistoryService;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** CRUD retains REQUIRED boundaries; internal matching/decoding does not start or suspend transactions. */
@Component
public class LedgerAnalysisHistories implements ManageLedgerAnalysisHistoryUseCase {
    private final ManageLedgerAnalysisHistoryService delegate;
    public LedgerAnalysisHistories(LedgerAnalysisHistoryStore store, LedgerAnalysisHistoryCodec codec) {
        delegate = new ManageLedgerAnalysisHistoryService(store, codec, Instant::now, ZoneId::systemDefault);
    }
    @Override @Transactional(readOnly = true)
    public HistoryPage search(Long owner, Filter filter) { return delegate.search(owner, filter); }
    @Override @Transactional(readOnly = true)
    public Detail detail(Long owner, Long id) { return delegate.detail(owner, id); }
    @Override @Transactional
    public int delete(Long owner, Long id) { return delegate.delete(owner, id); }
    @Override @Transactional
    public int deleteAll(Long owner) { return delegate.deleteAll(owner); }
    @Override public Optional<StoredHistory> completed(MatchKey key) { return delegate.completed(key); }
    @Override public Optional<StoredHistory> processing(MatchKey key) { return delegate.processing(key); }
    @Override public AnalysisResult readStored(StoredHistory history) { return delegate.readStored(history); }
    @Override public AnalysisResult readReusable(StoredHistory history) { return delegate.readReusable(history); }
}
