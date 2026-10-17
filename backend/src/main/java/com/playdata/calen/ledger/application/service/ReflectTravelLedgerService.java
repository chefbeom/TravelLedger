package com.playdata.calen.ledger.application.service;

import com.playdata.calen.common.exception.*;
import com.playdata.calen.ledger.application.port.in.ReflectTravelLedgerUseCase;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.domain.model.LedgerTravelData.*;
import com.playdata.calen.ledger.domain.policy.LedgerTravelPolicy;

/** Both directions participate in one write transaction supplied by the inbound adapter. */
public final class ReflectTravelLedgerService implements ReflectTravelLedgerUseCase {
    private final LedgerTravelStore store;
    private final LinkedLedgerWritePort writer;
    private final TravelBridgeEffectsPort effects;
    public ReflectTravelLedgerService(LedgerTravelStore store, LinkedLedgerWritePort writer, TravelBridgeEffectsPort effects) {
        this.store = store; this.writer = writer; this.effects = effects;
    }
    @Override public LedgerResult toLedger(Long owner, Long id) {
        var record = store.ownedTravel(owner, id).orElseThrow(() -> new NotFoundException("Travel ledger record not found."));
        // The legacy travel lookup treats null kind as ledger; the strict bridge check then rejects it.
        if (record.kind() != null && !"LEDGER".equals(record.kind())) throw new NotFoundException("Travel ledger record not found.");
        LedgerTravelPolicy.forward(owner, record);
        Long group = store.group(owner, LedgerTravelPolicy.GROUP);
        Long detail = store.detail(group, LedgerTravelPolicy.detailName(record.category()), LedgerTravelPolicy.createdDetailName(record.category()));
        Long payment = store.payment(owner, LedgerTravelPolicy.PAYMENT);
        var request = LedgerTravelPolicy.forwardWrite(record, group, detail, payment);
        return store.linkedLedger(owner, record.id()).map(entry -> writer.update(owner, entry, request)).orElseGet(() -> writer.create(owner, request));
    }
    @Override public TravelResult toTravel(Long owner, Long planId, Long entryId) {
        var plan = store.ownedPlan(owner, planId).orElseThrow(() -> new NotFoundException("Travel plan not found."));
        var entry = store.ownedEntry(owner, entryId).orElseThrow(() -> new BadRequestException("Ledger entry not found."));
        LedgerTravelPolicy.reverse(plan, entry);
        LinkedTravel existing = entry.travelRecord() == null ? null : store.linkedTravel(owner, entry.travelRecord()).orElse(null);
        if (existing != null && !existing.plan().equals(plan.id())) throw new BadRequestException("Ledger entry is already linked to another travel record.");
        var value = LedgerTravelPolicy.travelValue(entry, existing == null ? Boolean.FALSE : existing.shared());
        var record = store.saveTravel(plan.id(), existing == null ? null : existing.id(), value);
        writer.update(owner, entry.id(), LedgerTravelPolicy.reverseWrite(entry, plan.id(), record));
        var response = store.travelResult(record);
        effects.travelChanged(owner);
        return response;
    }
}
