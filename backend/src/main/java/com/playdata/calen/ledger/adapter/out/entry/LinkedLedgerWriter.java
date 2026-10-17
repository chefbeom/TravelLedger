package com.playdata.calen.ledger.adapter.out.entry;

import com.playdata.calen.ledger.adapter.support.LedgerTravelMapper;
import com.playdata.calen.ledger.application.port.out.LinkedLedgerWritePort;
import com.playdata.calen.ledger.domain.model.LedgerTravelData.*;
import com.playdata.calen.ledger.service.LedgerEntryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class LinkedLedgerWriter implements LinkedLedgerWritePort {
    private final LedgerEntryService entries;
    @Override public LedgerResult create(Long owner, Write request) { return LedgerTravelMapper.value(entries.create(owner, LedgerTravelMapper.request(request))); }
    @Override public LedgerResult update(Long owner, Long id, Write request) { return LedgerTravelMapper.value(entries.update(owner, id, LedgerTravelMapper.request(request))); }
}
