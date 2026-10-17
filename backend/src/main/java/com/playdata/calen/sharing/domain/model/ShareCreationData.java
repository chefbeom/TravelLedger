package com.playdata.calen.sharing.domain.model;

import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.RecordShareStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Values crossing sharing ports; no managed entities or HTTP/JSON contracts. */
public final class ShareCreationData {
    private ShareCreationData() {}

    public record Actor(Long id, String name) {}
    public record Group(Long id, String name) {}
    public record LedgerDraft(LocalDate entryDate, LocalTime entryTime, String title, String memo, BigDecimal amount,
            String foreignCurrencyCode, BigDecimal foreignAmount, BigDecimal exchangeRateToKrw, String entryType,
            Long categoryGroupId, Long categoryDetailId, Long paymentMethodId, Long travelPlanId, Long travelRecordId) {}
    public record LedgerSnapshot(Long id, LocalDate entryDate, LocalTime entryTime, String title, String memo,
            BigDecimal amount, String foreignCurrencyCode, BigDecimal foreignAmount, BigDecimal exchangeRateToKrw,
            LocalDate exchangeRateDate, String exchangeRateProvider, String entryType, Long categoryGroupId,
            String categoryGroupName, Long categoryDetailId, String categoryDetailName, Long paymentMethodId,
            String paymentMethodName, String paymentMethodKind, Long travelPlanId, Long travelRecordId) {}

    /** Fingerprints are opaque equality tokens; encoding remains an adapter concern. */
    public record Content(String title, LedgerSnapshot ledger, String fingerprint) {}
    public record Existing(Long id, Long groupId, RecordShareStatus status, String fingerprint) {}
    public record Submission(Group group, RecordShareKind kind, Long sourceId, Actor sender, Actor recipient,
            Content content, String shareMemo, LocalDateTime createdAt) {}
    public record Receipt(Long id, Long groupId, String groupName, RecordShareKind kind, Long sourceId,
            Actor sender, Actor recipient, RecordShareStatus status, String title, LedgerSnapshot ledger,
            Long importedLedgerEntryId, LocalDateTime createdAt, LocalDateTime respondedAt, String shareMemo) {}
}
