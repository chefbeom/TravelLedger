package com.playdata.calen.ledger.domain;

/** An adjustment is a separate, linked record; it is never a replacement for the original purchase. */
public enum LedgerAdjustmentType {
    PAYMENT_CANCELLATION,
    REFUND
}
