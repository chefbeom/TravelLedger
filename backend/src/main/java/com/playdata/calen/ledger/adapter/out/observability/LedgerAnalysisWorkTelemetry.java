package com.playdata.calen.ledger.adapter.out.observability;

import com.playdata.calen.ledger.ai.LedgerAiAnalysisMetrics;
import com.playdata.calen.ledger.application.port.out.LedgerAnalysisWorkMonitoring;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.slf4j.*;
import org.springframework.stereotype.Component;

/** Retain the existing logger category and bounded fields without a service dependency. */
@Component
@RequiredArgsConstructor
public class LedgerAnalysisWorkTelemetry implements LedgerAnalysisWorkMonitoring {
    private static final Logger log = LoggerFactory.getLogger("com.playdata.calen.ledger.ai.LedgerAiAnalysisService");
    private final LedgerAiAnalysisMetrics metrics;
    private record TimerSample(Timer.Sample sample) implements Sample {}
    @Override public Sample start() { var sample = metrics.startAiRequestTimer(); return sample == null ? null : new TimerSample(sample); }
    @Override public void record(Sample sample, String outcome) { metrics.recordAiRequest(sample == null ? null : ((TimerSample) sample).sample(), outcome); }
    @Override public void warn(Long history, RuntimeException failure) {
        log.warn("Ledger AI analysis failed: historyId={}, failureType={}", history, failure.getClass().getSimpleName());
    }
}
