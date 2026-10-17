package com.playdata.calen.ledger.application.service;

import com.playdata.calen.common.exception.*;
import com.playdata.calen.ledger.application.port.in.ManageLedgerAnalysisHistoryUseCase;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.*;
import com.playdata.calen.ledger.domain.model.LedgerLocalReportData.Report;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Historical access/result policy. No provider calls, ledger writes or terminal state updates. */
public final class ManageLedgerAnalysisHistoryService implements ManageLedgerAnalysisHistoryUseCase {
    private static final int MAX_HISTORY_PAGE_SIZE = 50;
    private final LedgerAnalysisHistoryStore store;
    private final LedgerAnalysisHistoryCodec codec;
    private final Supplier<Instant> now;
    private final Supplier<ZoneId> zone;

    public ManageLedgerAnalysisHistoryService(LedgerAnalysisHistoryStore store, LedgerAnalysisHistoryCodec codec,
            Supplier<Instant> now, Supplier<ZoneId> zone) {
        this.store = store; this.codec = codec; this.now = now; this.zone = zone;
    }
    @Override public HistoryPage search(Long owner, Filter filter) {
        store.requireActiveOwner(owner);
        if (filter.createdFrom() != null && filter.createdTo() != null && filter.createdFrom().isAfter(filter.createdTo()))
            throw new BadRequestException("조회 시작일은 종료일보다 이후일 수 없습니다.");
        int page = Math.max(0, filter.page());
        int size = Math.max(1, Math.min(filter.size(), MAX_HISTORY_PAGE_SIZE));
        LocalDateTime from = filter.createdFrom() == null ? null : filter.createdFrom().atStartOfDay();
        LocalDateTime toExclusive = filter.createdTo() == null ? null : filter.createdTo().plusDays(1).atStartOfDay();
        return store.search(owner, new Search(filter.mode(), filter.periodType(), from, toExclusive, filter.comparisonOnly(), page, size));
    }
    @Override public Detail detail(Long owner, Long id) {
        store.requireActiveOwner(owner);
        var history = store.find(owner, id).orElseThrow(() -> new NotFoundException("AI 분석 이력을 찾을 수 없습니다."));
        return new Detail(history.header(), readStored(history));
    }
    @Override public int delete(Long owner, Long id) {
        store.requireActiveOwner(owner);
        int count = store.delete(owner, id);
        if (count == 0) throw new NotFoundException("AI analysis history was not found.");
        return count;
    }
    @Override public int deleteAll(Long owner) {
        store.requireActiveOwner(owner);
        return store.deleteAll(owner);
    }
    @Override public Optional<StoredHistory> completed(MatchKey key) { return store.completed(key); }
    @Override public Optional<StoredHistory> processing(MatchKey key) { return store.processing(key); }
    @Override public AnalysisResult readStored(StoredHistory history) {
        if (history == null) return null;
        if (history.header().status() == LedgerAiAnalysisStatus.PROCESSING) return null;
        if (history.header().status() != LedgerAiAnalysisStatus.COMPLETED || !hasText(history.resultJson()))
            return buildUnreadableHistoryResult(history, "저장된 AI 분석 결과가 없습니다. 재분석을 실행해 주세요.");
        try {
            AnalysisResult result = codec.read(history.resultJson());
            return result == null ? buildUnreadableHistoryResult(history, "저장된 AI 분석 결과가 비어 있습니다. 재분석을 실행해 주세요.") : result;
        } catch (RuntimeException failure) {
            return buildUnreadableHistoryResult(history, "저장된 AI 분석 결과를 읽지 못했습니다. 재분석을 실행해 주세요.");
        }
    }
    @Override public AnalysisResult readReusable(StoredHistory history) {
        if (history == null || history.header().status() != LedgerAiAnalysisStatus.COMPLETED || !hasText(history.resultJson())) return null;
        try { return codec.read(history.resultJson()); } catch (RuntimeException failure) { return null; }
    }
    private AnalysisResult buildUnreadableHistoryResult(StoredHistory stored, String message) {
        Summary history = stored.header();
        String summary = firstNonBlank(history.summary(), message);
        Report report = new Report(
                summary,
                "이 저장 이력의 원본 분석 결과가 비어 있거나 현재 응답 형식과 맞지 않습니다. 재분석 버튼으로 새 결과를 생성해 주세요.",
                "저장된 상세 지표를 읽을 수 없어 금액 분석은 표시하지 않습니다.",
                List.of(),
                List.of(),
                List.of(message),
                "",
                List.of(),
                List.of(),
                List.of("이 이력을 재분석해 새 결과를 저장하세요."),
                List.of()
        );
        return new AnalysisResult(
                history.id(),
                history.mode(),
                history.periodType(),
                history.comparisonPreset(),
                history.from(),
                history.to(),
                history.compareFrom(),
                history.compareTo(),
                history.model(),
                history.createdAt() == null ? now.get() : history.createdAt().atZone(zone.get()).toInstant(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                BigDecimal.ZERO,
                List.of(),
                List.of(),
                List.of(),
                report,
                summary,
                List.of("저장된 분석 이력은 확인됐지만 상세 결과를 읽을 수 없습니다."),
                List.of(message),
                List.of("재분석을 실행해 현재 형식의 결과를 다시 저장하세요."),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                ""
        );
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String firstNonBlank(String primary, String fallback) { return hasText(primary) ? primary : fallback == null ? "" : fallback; }
}
