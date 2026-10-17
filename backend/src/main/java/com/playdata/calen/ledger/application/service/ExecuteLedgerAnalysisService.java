package com.playdata.calen.ledger.application.service;

import com.playdata.calen.ledger.application.port.in.*;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisPreparationData;
import com.playdata.calen.ledger.domain.policy.LedgerAiJobLifecyclePolicy;
import java.time.LocalDateTime;
import java.util.function.*;

/** Background execution only. The scheduler retains the lease/task lifetime and queue cleanup. */
public final class ExecuteLedgerAnalysisService implements ExecuteLedgerAnalysisUseCase {
    private final PrepareLedgerAnalysisDatasetUseCase datasets;
    private final LedgerAnalysisWorkStore jobs;
    private final LedgerAnalysisWorkFormats formats;
    private final LedgerSpendingAnalysisPort provider;
    private final FinalizeLedgerAiJobUseCase finalizer;
    private final LedgerAnalysisWorkMonitoring monitoring;
    private final Supplier<LocalDateTime> now;
    public ExecuteLedgerAnalysisService(PrepareLedgerAnalysisDatasetUseCase datasets, LedgerAnalysisWorkStore jobs,
            LedgerAnalysisWorkFormats formats, LedgerSpendingAnalysisPort provider, FinalizeLedgerAiJobUseCase finalizer,
            LedgerAnalysisWorkMonitoring monitoring, Supplier<LocalDateTime> now) {
        this.datasets = datasets; this.jobs = jobs; this.formats = formats; this.provider = provider;
        this.finalizer = finalizer; this.monitoring = monitoring; this.now = now;
    }
    @Override public void process(Long owner, Long history, AnalysisPlan plan, BooleanSupplier ownsLease) {
        if (!LedgerAiJobLifecyclePolicy.isProcessing(jobs.status(owner, history))) return;
        LedgerAnalysisWorkMonitoring.Sample timer = null;
        boolean recorded = false;
        try {
            var dataset = datasets.prepare(owner, new LedgerAnalysisPreparationData.AnalysisPlan(plan.mode(), plan.periodType(),
                    plan.comparisonPreset(), plan.primaryRange(), plan.comparisonRange()));
            var prepared = formats.prepare(plan, dataset);
            if (jobs.savePayload(owner, history, prepared.serialize()) == 0) return;
            timer = monitoring.start();
            var remote = provider.analyze(prepared.request());
            if (!ownsLease.getAsBoolean()) return;
            var response = formats.response(history, plan, dataset, remote);
            String summary = remote.summary();
            boolean completed = finalizer.complete(new Completion(new AnalysisJob(owner, history),
                    summary == null ? "" : summary, formats.serializeResult(response), null, now.get()));
            monitoring.record(timer, completed ? "success" : "discarded");
            recorded = true;
        } catch (RuntimeException failure) {
            if (!ownsLease.getAsBoolean()) return;
            if (timer != null && !recorded) monitoring.record(timer, "failure");
            finalizer.fail(new Failure(new AnalysisJob(owner, history), formats.failureText(failure), "AI analysis failed.", now.get()));
            monitoring.warn(history, failure);
        }
    }
}
