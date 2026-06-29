package com.playdata.calen.ledger.service;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.dto.LedgerTransactionAnomalyEntryResponse;
import com.playdata.calen.ledger.dto.LedgerTransactionAnomalyGroupResponse;
import com.playdata.calen.ledger.dto.LedgerTransactionAnomalyResponse;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerTransactionAnomalyService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_RANGE_DAYS = 366;

    private final AppUserService appUserService;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerTransactionAnomalyResponse findAnomalies(Long userId, LocalDate from, LocalDate to, Integer limit) {
        appUserService.getRequiredUser(userId);
        DateRange range = normalizeRange(from, to);
        int normalizedLimit = normalizeLimit(limit);
        List<LedgerEntry> entries = range == null
                ? ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(userId)
                : ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(
                        userId,
                        range.from(),
                        range.to()
                );

        List<LedgerTransactionAnomalyGroupResponse> groups = detectSameDaySameAmountDuplicates(entries);
        int totalGroups = groups.size();
        List<LedgerTransactionAnomalyGroupResponse> limitedGroups = groups.stream()
                .limit(normalizedLimit)
                .toList();

        return new LedgerTransactionAnomalyResponse(
                range == null ? null : range.from(),
                range == null ? null : range.to(),
                totalGroups,
                limitedGroups.size(),
                Instant.now(),
                limitedGroups
        );
    }

    private List<LedgerTransactionAnomalyGroupResponse> detectSameDaySameAmountDuplicates(List<LedgerEntry> entries) {
        Map<String, List<LedgerEntry>> grouped = new LinkedHashMap<>();
        for (LedgerEntry entry : entries) {
            if (entry.getEntryType() != EntryType.EXPENSE || entry.getAmount() == null || entry.getEntryDate() == null) {
                continue;
            }
            String normalizedTitle = normalizeTitle(entry.getTitle());
            if (normalizedTitle.isBlank()) {
                continue;
            }
            String key = String.join(
                    "|",
                    entry.getEntryDate().toString(),
                    normalizeAmount(entry.getAmount()),
                    entry.getEntryType().name(),
                    normalizedTitle
            );
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }

        return grouped.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= 2)
                .map(entry -> toDuplicateGroup(entry.getKey(), entry.getValue()))
                .sorted(Comparator
                        .comparing(LedgerTransactionAnomalyGroupResponse::entryCount).reversed()
                        .thenComparing(group -> group.entries().get(0).entryDate(), Comparator.reverseOrder())
                        .thenComparing(LedgerTransactionAnomalyGroupResponse::anomalyKey))
                .toList();
    }

    private LedgerTransactionAnomalyGroupResponse toDuplicateGroup(String anomalyKey, List<LedgerEntry> entries) {
        List<LedgerTransactionAnomalyEntryResponse> responseEntries = entries.stream()
                .sorted(Comparator
                        .comparing(LedgerEntry::getEntryDate)
                        .thenComparing(entry -> entry.getEntryTime() == null ? java.time.LocalTime.MIN : entry.getEntryTime())
                        .thenComparing(LedgerEntry::getId))
                .map(this::toEntryResponse)
                .toList();
        return new LedgerTransactionAnomalyGroupResponse(
                "DUPLICATE_SAME_DAY_AMOUNT_TITLE",
                responseEntries.size() >= 3 ? "high" : "medium",
                "Same-day expense entries have the same amount and a similar title.",
                anomalyKey,
                responseEntries.size(),
                responseEntries
        );
    }

    private LedgerTransactionAnomalyEntryResponse toEntryResponse(LedgerEntry entry) {
        CategoryGroup categoryGroup = entry.getCategoryGroup();
        CategoryDetail categoryDetail = entry.getCategoryDetail();
        PaymentMethod paymentMethod = entry.getPaymentMethod();
        return new LedgerTransactionAnomalyEntryResponse(
                entry.getId(),
                entry.getEntryDate(),
                entry.getEntryTime(),
                entry.getTitle(),
                entry.getAmount(),
                entry.getEntryType(),
                categoryGroup == null ? null : categoryGroup.getId(),
                categoryGroup == null ? null : categoryGroup.getName(),
                categoryDetail == null ? null : categoryDetail.getId(),
                categoryDetail == null ? null : categoryDetail.getName(),
                paymentMethod == null ? null : paymentMethod.getId(),
                paymentMethod == null ? null : paymentMethod.getName(),
                entry.getTravelPlanId(),
                entry.getTravelRecordId()
        );
    }

    private DateRange normalizeRange(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return null;
        }
        LocalDate normalizedFrom = from == null ? to : from;
        LocalDate normalizedTo = to == null ? from : to;
        if (normalizedFrom.isAfter(normalizedTo)) {
            throw new BadRequestException("Anomaly search start date cannot be after end date.");
        }
        if (normalizedFrom.plusDays(MAX_RANGE_DAYS).isBefore(normalizedTo)) {
            throw new BadRequestException("Anomaly search range cannot exceed 366 days.");
        }
        return new DateRange(normalizedFrom, normalizedTo);
    }

    private int normalizeLimit(Integer limit) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        if (value < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(value, MAX_LIMIT);
    }

    private String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String normalizeAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }
}