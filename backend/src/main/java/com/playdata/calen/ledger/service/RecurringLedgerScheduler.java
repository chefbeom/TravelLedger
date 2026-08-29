package com.playdata.calen.ledger.service;

import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringLedgerScheduler {

    private final RecurringLedgerService recurringLedgerService;

    @Value("${app.ledger.recurring.enabled:true}")
    private boolean enabled = true;

    @Value("${app.ledger.recurring.zone:Asia/Seoul}")
    private String zone = "Asia/Seoul";

    @Scheduled(
            cron = "${app.ledger.recurring.cron:0 5 0 * * *}",
            zone = "${app.ledger.recurring.zone:Asia/Seoul}"
    )
    public void processDueRecurringLedgers() {
        if (!enabled) {
            return;
        }

        try {
            int processed = recurringLedgerService.processDueDate(
                    LocalDate.now(ZoneId.of(zone))
            );
            log.info("Recurring ledger scheduler completed: processed={}", processed);
        } catch (Exception exception) {
            log.warn("Recurring ledger scheduler failed.", exception);
        }
    }
}
