package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerTravelData.*;

public interface LinkedLedgerWritePort {
    LedgerResult create(Long owner, Write request);
    LedgerResult update(Long owner, Long entry, Write request);
}
