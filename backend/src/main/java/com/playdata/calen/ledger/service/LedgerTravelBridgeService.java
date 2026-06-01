package com.playdata.calen.ledger.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelRecordType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LedgerTravelBridgeService {

    private static final String KRW = "KRW";
    private static final String TRAVEL_GROUP_NAME = "\uC5EC\uD589";
    private static final String DEFAULT_DETAIL_NAME = "\uAE30\uD0C0";
    private static final String DEFAULT_PAYMENT_METHOD_NAME = "\uC5EC\uD589 \uBBF8\uC9C0\uC815";
    private static final int TITLE_LIMIT = 120;
    private static final int MEMO_LIMIT = 500;

    private final LedgerEntryService ledgerEntryService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final PaymentMethodRepository paymentMethodRepository;

    @Transactional
    public LedgerEntryResponse upsertExpenseRecord(Long userId, TravelExpenseRecord record) {
        if (record.getRecordType() != TravelRecordType.LEDGER) {
            throw new BadRequestException("Only travel ledger records can be reflected into the household ledger.");
        }

        AppUser owner = record.getPlan().getOwner();
        if (!owner.getId().equals(userId)) {
            throw new BadRequestException("Travel record owner does not match the ledger owner.");
        }

        CategoryGroup group = resolveTravelGroup(owner);
        CategoryDetail detail = resolveTravelDetail(group, record.getCategory());
        PaymentMethod paymentMethod = resolveTravelPaymentMethod(owner);
        LedgerEntryRequest request = toLedgerEntryRequest(record, group, detail, paymentMethod);

        return ledgerEntryRepository.findByOwnerIdAndTravelRecordIdAndDeletedAtIsNull(userId, record.getId())
                .map(existing -> ledgerEntryService.update(userId, existing.getId(), request))
                .orElseGet(() -> ledgerEntryService.create(userId, request));
    }

    private CategoryGroup resolveTravelGroup(AppUser owner) {
        return categoryGroupRepository.findFirstByOwnerIdAndEntryTypeAndNameIgnoreCaseOrderByIdAsc(
                        owner.getId(),
                        EntryType.EXPENSE,
                        TRAVEL_GROUP_NAME
                )
                .map(this::activateGroup)
                .orElseGet(() -> {
                    CategoryGroup group = new CategoryGroup();
                    group.setOwner(owner);
                    group.setName(TRAVEL_GROUP_NAME);
                    group.setEntryType(EntryType.EXPENSE);
                    group.setDisplayOrder(Integer.MAX_VALUE - 20);
                    group.setActive(true);
                    return categoryGroupRepository.save(group);
                });
    }

    private CategoryGroup activateGroup(CategoryGroup group) {
        if (!group.isActive()) {
            group.setActive(true);
        }
        return group;
    }

    private CategoryDetail resolveTravelDetail(CategoryGroup group, String category) {
        String name = trimToDefault(category, DEFAULT_DETAIL_NAME);
        return categoryDetailRepository.findFirstByGroupIdAndNameIgnoreCaseOrderByIdAsc(group.getId(), name)
                .map(this::activateDetail)
                .orElseGet(() -> {
                    CategoryDetail detail = new CategoryDetail();
                    detail.setGroup(group);
                    detail.setName(limit(name, 80));
                    detail.setDisplayOrder(Integer.MAX_VALUE - 20);
                    detail.setActive(true);
                    return categoryDetailRepository.save(detail);
                });
    }

    private CategoryDetail activateDetail(CategoryDetail detail) {
        if (!detail.isActive()) {
            detail.setActive(true);
        }
        return detail;
    }

    private PaymentMethod resolveTravelPaymentMethod(AppUser owner) {
        return paymentMethodRepository.findFirstByOwnerIdAndNameIgnoreCaseOrderByIdAsc(owner.getId(), DEFAULT_PAYMENT_METHOD_NAME)
                .map(this::activatePaymentMethod)
                .orElseGet(() -> {
                    PaymentMethod paymentMethod = new PaymentMethod();
                    paymentMethod.setOwner(owner);
                    paymentMethod.setName(DEFAULT_PAYMENT_METHOD_NAME);
                    paymentMethod.setKind(PaymentMethodKind.OTHER);
                    paymentMethod.setDisplayOrder(Integer.MAX_VALUE - 20);
                    paymentMethod.setActive(true);
                    return paymentMethodRepository.save(paymentMethod);
                });
    }

    private PaymentMethod activatePaymentMethod(PaymentMethod paymentMethod) {
        if (!paymentMethod.isActive()) {
            paymentMethod.setActive(true);
        }
        return paymentMethod;
    }

    private LedgerEntryRequest toLedgerEntryRequest(
            TravelExpenseRecord record,
            CategoryGroup group,
            CategoryDetail detail,
            PaymentMethod paymentMethod
    ) {
        String currencyCode = normalizeCurrencyCode(record.getCurrencyCode());
        boolean foreignCurrency = currencyCode != null && !KRW.equals(currencyCode);

        return new LedgerEntryRequest(
                record.getExpenseDate(),
                record.getExpenseTime(),
                buildLedgerTitle(record),
                buildLedgerMemo(record),
                record.getAmountKrw() != null ? record.getAmountKrw() : BigDecimal.ZERO,
                foreignCurrency ? currencyCode : null,
                foreignCurrency ? record.getAmount() : null,
                foreignCurrency ? record.getExchangeRateToKrw() : null,
                EntryType.EXPENSE,
                group.getId(),
                detail.getId(),
                paymentMethod.getId(),
                record.getPlan().getId(),
                record.getId()
        );
    }

    private String buildLedgerTitle(TravelExpenseRecord record) {
        return limit("[\uC5EC\uD589] " + trimToDefault(record.getTitle(), record.getCategory()), TITLE_LIMIT);
    }

    private String buildLedgerMemo(TravelExpenseRecord record) {
        List<String> parts = new ArrayList<>();
        parts.add("\uC5EC\uD589: " + record.getPlan().getName());
        addIfPresent(parts, "\uC7A5\uC18C", record.getPlaceName());
        addIfPresent(parts, "\uC9C0\uC5ED", record.getRegion());
        addIfPresent(parts, "\uAD6D\uAC00", record.getCountry());
        addIfPresent(parts, "\uBA54\uBAA8", record.getMemo());
        return limit(String.join("\n", parts), MEMO_LIMIT);
    }

    private void addIfPresent(List<String> parts, String label, String value) {
        String text = trimToNull(value);
        if (text != null) {
            parts.add(label + ": " + text);
        }
    }

    private String normalizeCurrencyCode(String currencyCode) {
        String value = trimToNull(currencyCode);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private String trimToDefault(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String limit(String value, int maxLength) {
        String text = value == null ? "" : value;
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
