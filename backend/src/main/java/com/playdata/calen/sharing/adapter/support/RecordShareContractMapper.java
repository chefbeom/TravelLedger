package com.playdata.calen.sharing.adapter.support;

import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.sharing.application.port.in.CreateRecordShareCommand;
import com.playdata.calen.sharing.domain.model.ShareCreationData.*;
import com.playdata.calen.sharing.dto.RecordShareDtos;

/** Legacy wire contracts stay at the edge; core ports never expose them. */
public final class RecordShareContractMapper {
    private RecordShareContractMapper() {}

    public static CreateRecordShareCommand command(RecordShareDtos.Create request) {
        var edit = request.ledgerOverride();
        LedgerDraft draft = edit == null ? null : new LedgerDraft(edit.entryDate(), edit.entryTime(), edit.title(), edit.memo(),
                edit.amount(), edit.foreignCurrencyCode(), edit.foreignAmount(), edit.exchangeRateToKrw(),
                edit.entryType() == null ? null : edit.entryType().name(), edit.categoryGroupId(), edit.categoryDetailId(),
                edit.paymentMethodId(), edit.travelPlanId(), edit.travelRecordId());
        return new CreateRecordShareCommand(request.groupId(), request.kind(), request.sourceId(), request.recipientIds(), request.shareMemo(), draft);
    }

    public static LedgerEntryRequest ledgerRequest(LedgerDraft edit) {
        if (edit == null) return null;
        return new LedgerEntryRequest(edit.entryDate(), edit.entryTime(), edit.title(), edit.memo(), edit.amount(),
                edit.foreignCurrencyCode(), edit.foreignAmount(), edit.exchangeRateToKrw(),
                edit.entryType() == null ? null : EntryType.valueOf(edit.entryType()), edit.categoryGroupId(), edit.categoryDetailId(),
                edit.paymentMethodId(), edit.travelPlanId(), edit.travelRecordId());
    }

    public static LedgerSnapshot snapshot(LedgerEntryResponse entry) {
        if (entry == null) return null;
        return new LedgerSnapshot(entry.id(), entry.entryDate(), entry.entryTime(), entry.title(), entry.memo(), entry.amount(),
                entry.foreignCurrencyCode(), entry.foreignAmount(), entry.exchangeRateToKrw(), entry.exchangeRateDate(),
                entry.exchangeRateProvider(), entry.entryType() == null ? null : entry.entryType().name(), entry.categoryGroupId(),
                entry.categoryGroupName(), entry.categoryDetailId(), entry.categoryDetailName(), entry.paymentMethodId(),
                entry.paymentMethodName(), entry.paymentMethodKind() == null ? null : entry.paymentMethodKind().name(), entry.travelPlanId(), entry.travelRecordId());
    }

    public static LedgerEntryResponse ledgerResponse(LedgerSnapshot entry) {
        if (entry == null) return null;
        return new LedgerEntryResponse(entry.id(), entry.entryDate(), entry.entryTime(), entry.title(), entry.memo(), entry.amount(),
                entry.foreignCurrencyCode(), entry.foreignAmount(), entry.exchangeRateToKrw(), entry.exchangeRateDate(), entry.exchangeRateProvider(),
                entry.entryType() == null ? null : EntryType.valueOf(entry.entryType()), entry.categoryGroupId(), entry.categoryGroupName(),
                entry.categoryDetailId(), entry.categoryDetailName(), entry.paymentMethodId(), entry.paymentMethodName(),
                entry.paymentMethodKind() == null ? null : PaymentMethodKind.valueOf(entry.paymentMethodKind()), entry.travelPlanId(), entry.travelRecordId());
    }

    public static RecordShareDtos.Response response(Receipt item) {
        return new RecordShareDtos.Response(item.id(), item.groupId(), item.groupName(), item.kind(), item.sourceId(),
                item.sender().id(), item.sender().name(), item.recipient().id(), item.recipient().name(), item.status(), item.title(),
                ledgerResponse(item.ledger()), item.importedLedgerEntryId(), item.createdAt(), item.respondedAt(), item.shareMemo());
    }
}
