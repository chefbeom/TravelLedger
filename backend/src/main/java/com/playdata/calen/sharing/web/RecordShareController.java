package com.playdata.calen.sharing.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.sharing.domain.*;
import com.playdata.calen.sharing.dto.RecordShareDtos;
import com.playdata.calen.sharing.service.RecordSharingService;
import com.playdata.calen.travel.dto.TravelSharedExhibitDetailResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/record-shares") @RequiredArgsConstructor
public class RecordShareController {
    private final RecordSharingService service;
    @GetMapping("/groups")
    public List<RecordShareDtos.Group> groups(@AuthenticationPrincipal AppUserPrincipal user) { return service.getGroups(user.userId()); }
    @GetMapping("/counts")
    public RecordShareDtos.Counts counts(@AuthenticationPrincipal AppUserPrincipal user) { return service.counts(user.userId()); }
    @GetMapping
    public RecordShareDtos.Page list(@AuthenticationPrincipal AppUserPrincipal user, @RequestParam RecordShareKind kind,
            @RequestParam(defaultValue = "false") boolean sent, @RequestParam(required = false) RecordShareStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return service.list(user.userId(), kind, sent, status, page, size);
    }
    @PostMapping
    public List<RecordShareDtos.Response> create(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody RecordShareDtos.Create request) {
        return service.create(user.userId(), request);
    }
    @PostMapping("/{id}/accept-ledger")
    public RecordShareDtos.Response acceptLedger(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id, @Valid @RequestBody LedgerEntryRequest request) {
        return service.acceptLedger(user.userId(), id, request);
    }
    @PostMapping("/{id}/accept-travel")
    public RecordShareDtos.Response acceptTravel(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { return service.acceptTravel(user.userId(), id); }
    @PostMapping("/{id}/reject")
    public RecordShareDtos.Response reject(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { return service.reject(user.userId(), id); }
    @PostMapping("/{id}/cancel")
    public RecordShareDtos.Response cancel(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { return service.cancel(user.userId(), id); }
    @GetMapping("/{id}/travel")
    public TravelSharedExhibitDetailResponse travel(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long id) { return service.getTravel(user.userId(), id); }
}
