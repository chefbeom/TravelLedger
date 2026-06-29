package com.playdata.calen.ledger.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class LedgerAiAnalysisMetrics {

    private final LedgerAiAnalysisProperties properties;
    private final MeterRegistry meterRegistry;

    public LedgerAiAnalysisMetrics(
            LedgerAiAnalysisProperties properties,
            ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        this.properties = properties;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public Timer.Sample startAiRequestTimer() {
        return meterRegistry == null ? null : Timer.start(meterRegistry);
    }

    public void recordAiRequest(Timer.Sample sample, String status) {
        if (meterRegistry == null) {
            return;
        }
        String provider = providerLabel();
        Counter.builder("calen.ledger.ai.requests")
                .description("Ledger AI remote analysis requests")
                .tag("provider", provider)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
        if (sample != null) {
            sample.stop(Timer.builder("calen.ledger.ai.request")
                    .description("Ledger AI remote analysis request duration")
                    .tag("provider", provider)
                    .tag("status", status)
                    .register(meterRegistry));
        }
    }

    public String providerLabel() {
        try {
            return properties.provider().name().toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }
}