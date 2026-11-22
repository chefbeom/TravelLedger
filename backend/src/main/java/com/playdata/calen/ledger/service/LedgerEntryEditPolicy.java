package com.playdata.calen.ledger.service;

import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Pure edit/delete rules for an original ledger entry that may carry cancellation/refund history. */
final class LedgerEntryEditPolicy {
    private LedgerEntryEditPolicy() {
    }

    /** True when a cancellation or refund is still in effect (so financial fields are locked). */
    static boolean hasActiveAdjustment(LedgerEntry origin, List<LedgerEntry> records) {
        BigDecimal refunded = origin.getRefundedPrincipal() == null ? BigDecimal.ZERO : origin.getRefundedPrincipal();
        return origin.getCancelledAt() != null || refunded.signum() > 0 || origin.isFinanciallyExcluded()
                || records.stream().anyMatch(record -> record.getAdjustmentVoidedAt() == null);
    }

    static Optional<LocalDate> earliestActiveRecordDate(List<LedgerEntry> records) {
        return records.stream()
                .filter(record -> record.getAdjustmentVoidedAt() == null)
                .map(LedgerEntry::getEntryDate)
                .min(LocalDate::compareTo);
    }

    /** Names of locked fields that the request would change, in a fixed display order, without duplicates. */
    static List<String> lockedFieldChanges(LedgerEntry entry, LedgerEntryRequest request) {
        List<String> changed = new ArrayList<>();
        if (request.entryType() != entry.getEntryType()) add(changed, "수입/지출 구분");
        if (entry.getEntryType() == EntryType.EXPENSE && entry.getPaymentMethod() != null
                && !Objects.equals(request.paymentMethodId(), entry.getPaymentMethod().getId())) {
            add(changed, "결제수단");
        }
        String requestedCurrency = currency(request.foreignCurrencyCode());
        String storedCurrency = currency(entry.getForeignCurrencyCode());
        if (!Objects.equals(requestedCurrency, storedCurrency)) {
            add(changed, "외화 정보");
        } else if (storedCurrency != null) {
            if (!sameAmount(request.foreignAmount(), entry.getForeignAmount())
                    || request.exchangeRateToKrw() == null
                    || !sameAmount(request.exchangeRateToKrw(), entry.getExchangeRateToKrw())) {
                add(changed, "외화 정보");
            }
        } else if (!sameAmount(request.amount(), entry.getAmount())) {
            add(changed, "금액");
        }
        Integer months = request.installmentMonths();
        if (months != null && (months == 0 ? entry.getInstallmentMonths() != null : !months.equals(entry.getInstallmentMonths()))) {
            add(changed, "할부");
        }
        if (request.installmentInterestType() != null
                && !request.installmentInterestType().equals(entry.getInstallmentInterestType())) {
            add(changed, "할부");
        }
        if (!Objects.equals(request.travelPlanId(), entry.getTravelPlanId())
                || !Objects.equals(request.travelRecordId(), entry.getTravelRecordId())) {
            add(changed, "여행 연결");
        }
        return changed;
    }

    private static void add(List<String> list, String name) {
        if (!list.contains(name)) list.add(name);
    }

    private static String currency(String code) {
        if (code == null || code.isBlank() || "KRW".equalsIgnoreCase(code.trim())) return null;
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == null && b == null;
        return a.compareTo(b) == 0;
    }
}
