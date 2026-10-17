package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.*;
import com.playdata.calen.travel.application.port.in.ReadOwnedTravelPlansUseCase;
import com.playdata.calen.travel.dto.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class OwnedTravelPlansController {
    private final ReadOwnedTravelPlansUseCase reads;
    @GetMapping("/plans") public List<TravelPlanSummaryResponse> plans(@AuthenticationPrincipal AppUserPrincipal owner) { return reads.plans(owner.userId()).stream().map(TravelOwnedPlansMapper::summary).toList(); }
    @GetMapping("/plans/{planId}") public TravelPlanDetailResponse detail(@AuthenticationPrincipal AppUserPrincipal owner, @PathVariable Long planId) { return TravelPlanDetailMapper.response(reads.detail(owner.userId(), planId), id -> "/api/travel/media/" + id + "/content"); }
    @GetMapping("/portfolio") public TravelPortfolioResponse portfolio(@AuthenticationPrincipal AppUserPrincipal owner) { return TravelOwnedPlansMapper.portfolio(reads.portfolio(owner.userId())); }
}
