package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.TravelBudgetMapper;
import com.playdata.calen.travel.application.port.in.WriteTravelBudgetUseCase;
import com.playdata.calen.travel.dto.TravelBudgetItemRequest;
import com.playdata.calen.travel.dto.TravelBudgetItemResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelBudgetController {
    private final WriteTravelBudgetUseCase budgets;

    @PostMapping("/plans/{planId}/budget-items")
    public TravelBudgetItemResponse create(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long planId,
            @Valid @RequestBody TravelBudgetItemRequest request) {
        return TravelBudgetMapper.response(budgets.create(user.userId(), planId, TravelBudgetMapper.draft(request)));
    }

    @PutMapping("/budget-items/{itemId}")
    public TravelBudgetItemResponse update(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long itemId,
            @Valid @RequestBody TravelBudgetItemRequest request) {
        return TravelBudgetMapper.response(budgets.update(user.userId(), itemId, TravelBudgetMapper.draft(request)));
    }

    @DeleteMapping("/budget-items/{itemId}")
    public void delete(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long itemId) { budgets.delete(user.userId(), itemId); }
}
