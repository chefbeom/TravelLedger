package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.TravelReferenceMapper;
import com.playdata.calen.travel.application.port.in.ReadTravelReferenceUseCase;
import com.playdata.calen.travel.dto.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelReferenceController {
    private final ReadTravelReferenceUseCase reads;
    @GetMapping("/categories") public TravelCategoryCatalogResponse catalog(@AuthenticationPrincipal AppUserPrincipal owner) { return TravelReferenceMapper.catalog(reads.catalog(owner.userId())); }
    @GetMapping("/exchange-rates") public List<TravelExchangeRateResponse> rates(@AuthenticationPrincipal AppUserPrincipal owner, @RequestParam(required = false) String currencies) { return reads.rates(owner.userId(), currencies).stream().map(TravelReferenceMapper::quote).toList(); }
}
