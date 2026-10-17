package com.playdata.calen.ledger.application.port.out;

public interface LedgerAnalysisWorkMonitoring {
    interface Sample {}
    Sample start();
    /** Null is the original disabled-metering sample, not a reason to change flow. */
    void record(Sample sample, String outcome);
    void warn(Long history, RuntimeException failure);
}
