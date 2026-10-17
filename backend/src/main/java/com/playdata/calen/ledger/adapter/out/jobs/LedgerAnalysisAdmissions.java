package com.playdata.calen.ledger.adapter.out.jobs;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.adapter.support.*;
import com.playdata.calen.ledger.ai.*;
import com.playdata.calen.ledger.application.port.in.ManageLedgerAnalysisHistoryUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerAnalysisAdmissionPort;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.*;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Optional;
import java.util.function.*;
import org.springframework.util.DigestUtils;

/** Request-local runtime binding; no second admission/task registry and no encompassing transaction. */
public final class LedgerAnalysisAdmissions {
    private LedgerAnalysisAdmissions() {}
    public static LedgerAnalysisAdmissionPort operation(Long userId, AnalysisPlan plan, AppUserService users,
            LedgerAiAnalysisProperties properties, LedgerAiAnalysisMetrics metrics, WorkLeaseService leases,
            LedgerAiAnalysisHistoryRepository histories, ManageLedgerAnalysisHistoryUseCase queries,
            LongConsumer submit, BiConsumer<Long, RuntimeException> failed) {
        return new LedgerAnalysisAdmissionPort() {
            private AppUser owner;
            private LedgerAiAnalysisHistory pending;
            @Override public void requireActiveOwner() { owner = users.getRequiredUser(userId); }
            @Override public void requireConfigured() {
                if (!properties.isFeatureConfigured(LedgerAiFeature.LEDGER_ANALYSIS))
                    throw new BadRequestException(properties.featureStatusMessage(LedgerAiFeature.LEDGER_ANALYSIS));
            }
            @Override public boolean usesDistributedAdmission() { return leases != null; }
            @Override public AdmissionLease tryAdmission() {
                String key = "analysis-admission:" + DigestUtils.md5DigestAsHex((userId + ":" + plan).getBytes(StandardCharsets.UTF_8));
                var lease = leases.tryAcquire(key, Duration.ofMinutes(2));
                return lease == null ? null : lease::close;
            }
            @Override public String focusPrompt() { return plan.focusPrompt(); }
            @Override public LocalDateTime now() { return LocalDateTime.now(); }
            @Override public Optional<Detail> processing(LocalDateTime since) {
                return queries.processing(key(since)).map(history -> new Detail(history.header(), null));
            }
            @Override public Optional<Detail> reusable(LocalDateTime since) {
                return queries.completed(key(since)).flatMap(history -> {
                    var result = queries.readReusable(history);
                    return result == null ? Optional.empty() : Optional.of(new Detail(history.header(), result));
                });
            }
            @Override public void createProcessing() {
                var draft = LedgerAnalysisHistoryDrafts.create(owner, plan, properties, metrics);
                draft.setStatus(LedgerAiAnalysisStatus.PROCESSING);
                draft.setSummary("AI analysis is processing.");
                pending = histories.save(draft);
            }
            @Override public void submit() { submit.accept(pending.getId()); }
            @Override public void submissionFailed(RuntimeException failure) { failed.accept(pending.getId(), failure); }
            @Override public Detail pendingDetail() { return new Detail(LedgerAnalysisHistoryMapper.summary(pending), null); }
            private MatchKey key(LocalDateTime since) {
                return new MatchKey(userId, metrics.providerLabel(), properties.getModel(), plan.mode(), plan.periodType(),
                        plan.primaryRange().from(), plan.primaryRange().to(), plan.comparisonRange() == null ? null : plan.comparisonRange().from(),
                        plan.comparisonRange() == null ? null : plan.comparisonRange().to(), since);
            }
        };
    }
}
