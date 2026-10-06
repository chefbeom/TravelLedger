package com.playdata.calen.ledger.embedding.sync;

import com.playdata.calen.common.embedding.EmbeddingProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app.embedding.sync")
@Validated
@Getter
@Setter
public class LedgerEmbeddingSyncProperties {
    // Can capture a backlog while API calls are disabled; requires migration 032 first.
    private boolean captureEnabled = false;
    private Duration pollInterval = Duration.ofSeconds(5);
    private Duration leaseDuration = Duration.ofMinutes(15);
    private Duration retryBaseDelay = Duration.ofSeconds(10);
    private Duration retryMaxDelay = Duration.ofMinutes(15);
    private Duration recoveryDelay = Duration.ofMinutes(1);
    private Duration reconcileInterval = Duration.ofHours(24);
    @Min(1) @Max(100)
    private int maxAttempts = 8;

    @AssertTrue(message = "embedding sync durations must be positive, and retry-max-delay must not be less than retry-base-delay")
    public boolean isDurationsValid() {
        return positive(pollInterval) && positive(leaseDuration) && positive(retryBaseDelay)
                && positive(retryMaxDelay) && positive(recoveryDelay) && positive(reconcileInterval)
                && retryMaxDelay.compareTo(retryBaseDelay) >= 0;
    }

    public void validateLeaseBudget(EmbeddingProperties api) {
        if (api.isEnabled() && leaseDuration.compareTo(api.getReadTimeout().plus(api.getConnectTimeout())
                .multipliedBy(2).plusSeconds(60 + 30L * api.getBatchSize())) <= 0) {
            throw new IllegalArgumentException("embedding sync lease must exceed two API timeouts, source-read budget and 60 seconds");
        }
    }

    private boolean positive(Duration value) { return value != null && value.toMillis() > 0; }
}
