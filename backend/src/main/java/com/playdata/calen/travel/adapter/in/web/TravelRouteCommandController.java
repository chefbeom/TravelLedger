package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.TravelRouteMapper;
import com.playdata.calen.travel.application.port.in.WriteTravelRouteUseCase;
import com.playdata.calen.travel.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/travel")
public class TravelRouteCommandController {
    private final WriteTravelRouteUseCase routes;
    @PostMapping("/plans/{planId}/routes")
    public TravelRouteSegmentResponse create(@AuthenticationPrincipal AppUserPrincipal user,@PathVariable Long planId,
            @Valid @RequestBody TravelRouteSegmentRequest request) {
        return TravelRouteMapper.response(routes.create(user.userId(),planId,TravelRouteMapper.draft(request)));
    }
    @PutMapping("/routes/{routeId}")
    public TravelRouteSegmentResponse update(@AuthenticationPrincipal AppUserPrincipal user,@PathVariable Long routeId,
            @Valid @RequestBody TravelRouteSegmentRequest request) {
        return TravelRouteMapper.response(routes.update(user.userId(),routeId,TravelRouteMapper.draft(request)));
    }
    @DeleteMapping("/routes/{routeId}")
    public void delete(@AuthenticationPrincipal AppUserPrincipal user,@PathVariable Long routeId) {routes.delete(user.userId(),routeId);}
}
