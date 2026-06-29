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
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private static final int MIN_REPEATED_PAYMENT_MONTHS = 3;
    private static final int HIGH_TRAVEL_OUT_OF_RANGE_DAYS = 7;
    private static final int MIN_UNUSUAL_EXPENSE_BASELINE_COUNT = 5;
    private static final BigDecimal UNUSUAL_EXPENSE_MULTIPLIER = BigDecimal.valueOf(3);
    private static final BigDecimal HIGH_UNUSUAL_EXPENSE_MULTIPLIER = BigDecimal.valueOf(5);
    private static final BigDecimal MIN_UNUSUAL_EXPENSE_AMOUNT = BigDecimal.valueOf(50_000);

    private final AppUserService appUserService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TravelPlanRepository travelPlanRepository;

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
        Map<Long, TravelPlan> ownerTravelPlans = ownerTravelPlansById(userId);

        List<LedgerTransactionAnomalyGroupResponse> groups = new ArrayList<>();
        groups.addAll(detectSameDaySameAmountDuplicates(entries));
        groups.addAll(detectRepeatedSameAmountExpenses(entries));
        groups.addAll(detectTravelOutOfRangeExpenses(entries, ownerTravelPlans));
        groups.addAll(detectUnusuallyLargeExpenses(entries));
        groups = groups.stream()
                .sorted(this::compareGroups)
                .toList();
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
                .toList();
    }

    private List<LedgerTransactionAnomalyGroupResponse> detectRepeatedSameAmountExpenses(List<LedgerEntry> entries) {
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
                    "repeated-payment",
                    normalizeAmount(expenseAmount(entry)),
                    normalizedTitle
            );
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }

        return grouped.entrySet().stream()
                .filter(entry -> distinctMonthCount(entry.getValue()) >= MIN_REPEATED_PAYMENT_MONTHS)
                .map(entry -> toRepeatedSameAmountGroup(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<LedgerTransactionAnomalyGroupResponse> detectTravelOutOfRangeExpenses(
            List<LedgerEntry> entries,
            Map<Long, TravelPlan> ownerTravelPlans
    ) {
        if (ownerTravelPlans.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .filter(entry -> entry.getEntryType() == EntryType.EXPENSE)
                .filter(entry -> entry.getEntryDate() != null)
                .filter(entry -> entry.getTravelPlanId() != null)
                .filter(entry -> isOutsideTravelRange(entry, ownerTravelPlans.get(entry.getTravelPlanId())))
                .sorted(Comparator
                        .comparing(LedgerEntry::getEntryDate, Comparator.reverseOrder())
                        .thenComparing(LedgerEntry::getId))
                .map(entry -> toTravelOutOfRangeGroup(entry, ownerTravelPlans.get(entry.getTravelPlanId())))
                .toList();
    }
    private List<LedgerTransactionAnomalyGroupResponse> detectUnusuallyLargeExpenses(List<LedgerEntry> entries) {
        List<LedgerEntry> expenses = entries.stream()
                .filter(entry -> entry.getEntryType() == EntryType.EXPENSE)
                .filter(entry -> entry.getEntryDate() != null)
                .filter(entry -> expenseAmount(entry).compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (expenses.size() < MIN_UNUSUAL_EXPENSE_BASELINE_COUNT) {
            return List.of();
        }

        BigDecimal median = medianExpenseAmount(expenses);
        if (median.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }
        BigDecimal threshold = median.multiply(UNUSUAL_EXPENSE_MULTIPLIER).max(MIN_UNUSUAL_EXPENSE_AMOUNT);
        BigDecimal highThreshold = median.multiply(HIGH_UNUSUAL_EXPENSE_MULTIPLIER).max(MIN_UNUSUAL_EXPENSE_AMOUNT);

        return expenses.stream()
                .filter(entry -> expenseAmount(entry).compareTo(threshold) >= 0)
                .sorted(Comparator
                        .comparing(this::expenseAmount, Comparator.reverseOrder())
                        .thenComparing(LedgerEntry::getEntryDate, Comparator.reverseOrder())
                        .thenComparing(LedgerEntry::getId))
                .map(entry -> toUnusuallyLargeExpenseGroup(entry, median, highThreshold))
                .toList();
    }

    private LedgerTransactionAnomalyGroupResponse toDuplicateGroup(String anomalyKey, List<LedgerEntry> entries) {
        List<LedgerTransactionAnomalyEntryResponse> responseEntries = sortedEntryResponses(entries);
        return new LedgerTransactionAnomalyGroupResponse(
                "DUPLICATE_SAME_DAY_AMOUNT_TITLE",
                responseEntries.size() >= 3 ? "high" : "medium",
                "Same-day expense entries have the same amount and a similar title.",
                anomalyKey,
                responseEntries.size(),
                responseEntries
        );
    }

    private LedgerTransactionAnomalyGroupResponse toRepeatedSameAmountGroup(String anomalyKey, List<LedgerEntry> entries) {
        List<LedgerTransactionAnomalyEntryResponse> responseEntries = sortedEntryResponses(entries);
        int monthCount = distinctMonthCount(entries);
        return new LedgerTransactionAnomalyGroupResponse(
                "REPEATED_SAME_AMOUNT_TITLE",
                responseEntries.size() >= 6 || monthCount >= 6 ? "high" : "medium",
                "Expense entries repeat with the same amount and a similar title across multiple months.",
                anomalyKey,
                responseEntries.size(),
                responseEntries
        );
    }

    private LedgerTransactionAnomalyGroupResponse toTravelOutOfRangeGroup(LedgerEntry entry, TravelPlan plan) {
        int daysOutside = daysOutsideRange(entry.getEntryDate(), plan);
        return new LedgerTransactionAnomalyGroupResponse(
                "TRAVEL_OUT_OF_RANGE_EXPENSE",
                daysOutside > HIGH_TRAVEL_OUT_OF_RANGE_DAYS ? "high" : "medium",
                "Travel-linked expense date falls outside the linked travel plan date range.",
                String.join(
                        "|",
                        "travel-out-of-range",
                        String.valueOf(plan.getId()),
                        plan.getStartDate().toString(),
                        plan.getEndDate().toString(),
                        entry.getEntryDate().toString(),
                        String.valueOf(entry.getId())
                ),
                1,
                List.of(toEntryResponse(entry))
        );
    }
    private LedgerTransactionAnomalyGroupResponse toUnusuallyLargeExpenseGroup(
            LedgerEntry entry,
            BigDecimal median,
            BigDecimal highThreshold
    ) {
        BigDecimal amount = expenseAmount(entry);
        return new LedgerTransactionAnomalyGroupResponse(
                "UNUSUALLY_LARGE_EXPENSE",
                amount.compareTo(highThreshold) >= 0 ? "high" : "medium",
                "Expense amount is unusually high compared with the user's median expense in the selected range.",
                String.join(
                        "|",
                        "large-expense",
                        entry.getEntryDate().toString(),
                        String.valueOf(entry.getId()),
                        normalizeAmount(amount),
                        "median",
                        normalizeAmount(median)
                ),
                1,
                List.of(toEntryResponse(entry))
        );
    }

    private List<LedgerTransactionAnomalyEntryResponse> sortedEntryResponses(List<LedgerEntry> entries) {
        return entries.stream()
                .sorted(Comparator
                        .comparing(LedgerEntry::getEntryDate)
                        .thenComparing(entry -> entry.getEntryTime() == null ? java.time.LocalTime.MIN : entry.getEntryTime())
                        .thenComparing(LedgerEntry::getId))
                .map(this::toEntryResponse)
                .toList();
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

    private int compareGroups(LedgerTransactionAnomalyGroupResponse left, LedgerTransactionAnomalyGroupResponse right) {
        int severity = Integer.compare(severityRank(left.severity()), severityRank(right.severity()));
        if (severity != 0) {
            return severity;
        }
        int date = firstEntryDate(right).compareTo(firstEntryDate(left));
        if (date != 0) {
            return date;
        }
        int count = Integer.compare(right.entryCount(), left.entryCount());
        if (count != 0) {
            return count;
        }
        return left.anomalyKey().compareTo(right.anomalyKey());
    }

    private int severityRank(String severity) {
        return switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
    }

    private LocalDate firstEntryDate(LedgerTransactionAnomalyGroupResponse group) {
        if (group.entries() == null || group.entries().isEmpty() || group.entries().get(0).entryDate() == null) {
            return LocalDate.MIN;
        }
        return group.entries().get(0).entryDate();
    }

    private BigDecimal medianExpenseAmount(List<LedgerEntry> expenses) {
        List<BigDecimal> amounts = expenses.stream()
                .map(this::expenseAmount)
                .sorted()
                .toList();
        return amounts.get(amounts.size() / 2);
    }

    private Map<Long, TravelPlan> ownerTravelPlansById(Long userId) {
        List<TravelPlan> travelPlans = travelPlanRepository.findAllByOwnerIdOrderByStartDateDescIdDesc(userId);
        if (travelPlans == null || travelPlans.isEmpty()) {
            return Map.of();
        }
        return travelPlans.stream()
                .filter(plan -> plan.getId() != null)
                .filter(plan -> plan.getStartDate() != null)
                .filter(plan -> plan.getEndDate() != null)
                .collect(Collectors.toMap(
                        TravelPlan::getId,
                        Function.identity(),
                        (left, right) -> left
                ));
    }

    private boolean isOutsideTravelRange(LedgerEntry entry, TravelPlan plan) {
        return plan != null
                && (entry.getEntryDate().isBefore(plan.getStartDate()) || entry.getEntryDate().isAfter(plan.getEndDate()));
    }

    private int daysOutsideRange(LocalDate entryDate, TravelPlan plan) {
        if (entryDate.isBefore(plan.getStartDate())) {
            return Math.toIntExact(ChronoUnit.DAYS.between(entryDate, plan.getStartDate()));
        }
        return Math.toIntExact(ChronoUnit.DAYS.between(plan.getEndDate(), entryDate));
    }
    private int distinctMonthCount(List<LedgerEntry> entries) {
        Set<YearMonth> months = entries.stream()
                .filter(entry -> entry.getEntryDate() != null)
                .map(entry -> YearMonth.from(entry.getEntryDate()))
                .collect(Collectors.toSet());
        return months.size();
    }

    private BigDecimal expenseAmount(LedgerEntry entry) {
        return entry.getAmount() == null ? BigDecimal.ZERO : entry.getAmount().abs();
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