package com.playdata.calen.ledger.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app.ledger.ai.requests")
@Validated
@Getter
@Setter
public class LedgerAiRequestProperties {
    @Min(1) @Max(1000)
    private int maxConcurrent = 1;
    @Min(0) @Max(10000)
    private int maxWaiting = 20;
    @NotNull
    private Duration waitTimeout = Duration.ofSeconds(30);

    @AssertTrue(message = "wait-timeout must be nonnegative and no longer than 30 minutes")
    public boolean isWaitTimeoutValid() {
        return waitTimeout != null && !waitTimeout.isNegative() && waitTimeout.compareTo(Duration.ofMinutes(30)) <= 0;
    }
}
