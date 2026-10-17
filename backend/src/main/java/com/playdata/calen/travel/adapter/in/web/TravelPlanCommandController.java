package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.TravelPlanMapper;
import com.playdata.calen.travel.application.port.in.WriteTravelPlanUseCase;
import com.playdata.calen.travel.dto.TravelPlanRequest;
import com.playdata.calen.travel.dto.TravelPlanSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/travel")
public class TravelPlanCommandController {
    private final WriteTravelPlanUseCase plans;
    @PostMapping("/plans")
    public TravelPlanSummaryResponse create(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody TravelPlanRequest request) {
        return TravelPlanMapper.response(plans.create(user.userId(), TravelPlanMapper.draft(request)));
    }
    @PutMapping("/plans/{planId}")
    public TravelPlanSummaryResponse update(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long planId, @Valid @RequestBody TravelPlanRequest request) {
        return TravelPlanMapper.response(plans.update(user.userId(), planId, TravelPlanMapper.draft(request)));
    }
    @DeleteMapping("/plans/{planId}")
    public void delete(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long planId) { plans.delete(user.userId(), planId); }
}
