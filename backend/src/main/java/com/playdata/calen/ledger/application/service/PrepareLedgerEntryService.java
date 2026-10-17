package com.playdata.calen.ledger.application.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.application.port.in.PrepareLedgerEntryUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerExchangeRatePort;
import com.playdata.calen.ledger.application.port.out.LedgerReferencesPort;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.domain.model.LedgerPreparationData.*;
import com.playdata.calen.ledger.domain.policy.LedgerMoneyPolicy;
import com.playdata.calen.ledger.domain.policy.LedgerTextPolicy;

public final class PrepareLedgerEntryService implements PrepareLedgerEntryUseCase {
    private final LedgerReferencesPort references;
    private final LedgerExchangeRatePort rates;

    public PrepareLedgerEntryService(LedgerReferencesPort references, LedgerExchangeRatePort rates) {
        this.references = references;
        this.rates = rates;
    }

    @Override public Prepared prepare(Long ownerId, Draft input, boolean sharingPreview) {
        Draft draft = sharingPreview ? input.withoutTravel() : input;
        if (sharingPreview && LedgerMoneyPolicy.foreign(LedgerMoneyPolicy.normalizeCurrency(draft.foreignCurrencyCode()))
                && !LedgerMoneyPolicy.positive(draft.exchangeRateToKrw())) {
            throw new BadRequestException("수정 공유할 외화 거래의 적용 환율을 입력해 주세요.");
        }
        Category group = references.category(ownerId, draft.categoryGroupId());
        if (group.type() != draft.entryType()) {
            throw new BadRequestException("대분류의 수입/지출 구분이 거래 타입과 일치하지 않습니다.");
        }
        Payment payment = sharingPreview && draft.entryType() == EntryType.INCOME
                ? new Payment(null, "-", PaymentMethodKind.OTHER) : references.payment(ownerId, draft.entryType(), draft.paymentMethodId());
        Detail detail = draft.categoryDetailId() == null ? null : references.detail(ownerId, draft.categoryDetailId());
        if (detail != null && !detail.groupId().equals(group.id())) {
            throw new BadRequestException("소분류가 선택한 대분류에 속하지 않습니다.");
        }
        var text = LedgerTextPolicy.sanitize(draft.title(), draft.memo());
        Exchange exchange = exchange(draft);
        if (draft.travelPlanId() != null && draft.travelRecordId() == null) {
            TravelPeriod period = references.travelPeriod(ownerId, draft.travelPlanId());
            if (draft.entryDate().isBefore(period.from()) || draft.entryDate().isAfter(period.to())) {
                throw new BadRequestException("Travel ledger entries must stay within the selected travel period.");
            }
        }
        return new Prepared(draft, text.title(), text.memo(), exchange, group.id(), detail == null ? null : detail.id(), payment);
    }

    private Exchange exchange(Draft draft) {
        String currency = LedgerMoneyPolicy.normalizeCurrency(draft.foreignCurrencyCode());
        if (!LedgerMoneyPolicy.foreign(currency)) return new Exchange(draft.amount(), null, null, null, null, null);
        LedgerMoneyPolicy.requireForeignAmount(draft.foreignAmount());
        Rate rate = LedgerMoneyPolicy.positive(draft.exchangeRateToKrw())
                ? new Rate(draft.exchangeRateToKrw(), draft.entryDate(), "Provided") : rates.requiredRate(currency, draft.entryDate());
        return LedgerMoneyPolicy.calculate(currency, draft.foreignAmount(), rate.amount(), rate.date() == null ? draft.entryDate() : rate.date(), rate.provider());
    }
}
