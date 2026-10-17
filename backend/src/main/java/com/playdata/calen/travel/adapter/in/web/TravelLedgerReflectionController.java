package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.adapter.support.LedgerTravelMapper;
import com.playdata.calen.ledger.application.port.in.ReflectTravelLedgerUseCase;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.travel.adapter.support.TravelLedgerReflectionMapper;
import com.playdata.calen.travel.dto.TravelExpenseRecordResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelLedgerReflectionController {
    private final ReflectTravelLedgerUseCase reflection;
    @PostMapping("/records/{recordId}/ledger-entry")
    public LedgerEntryResponse toLedger(@AuthenticationPrincipal AppUserPrincipal owner, @PathVariable Long recordId) {
        return LedgerTravelMapper.response(reflection.toLedger(owner.userId(), recordId));
    }
    @PostMapping("/plans/{planId}/ledger-entries/{entryId}/record")
    public TravelExpenseRecordResponse toTravel(@AuthenticationPrincipal AppUserPrincipal owner, @PathVariable Long planId, @PathVariable Long entryId) {
        return TravelLedgerReflectionMapper.response(reflection.toTravel(owner.userId(), planId, entryId));
    }
}
