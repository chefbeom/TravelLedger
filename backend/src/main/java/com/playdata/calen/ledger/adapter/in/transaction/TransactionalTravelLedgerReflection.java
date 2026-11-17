package com.playdata.calen.ledger.adapter.in.transaction;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.ledger.application.port.in.ReflectTravelLedgerUseCase;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.application.service.ReflectTravelLedgerService;
import com.playdata.calen.ledger.domain.model.LedgerTravelData.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class TransactionalTravelLedgerReflection implements ReflectTravelLedgerUseCase {
    private final ReflectTravelLedgerService delegate;
    private final AppUserService users;
    public TransactionalTravelLedgerReflection(LedgerTravelStore store, LinkedLedgerWritePort writer, TravelBridgeEffectsPort effects, AppUserService users) {
        delegate = new ReflectTravelLedgerService(store, writer, effects);
        this.users = users;
    }
    @Override public LedgerResult toLedger(Long owner, Long record) { users.getRequiredUserForWrite(owner); return delegate.toLedger(owner, record); }
    @Override public TravelResult toTravel(Long owner, Long plan, Long entry) { users.getRequiredUserForWrite(owner); return delegate.toTravel(owner, plan, entry); }
}
