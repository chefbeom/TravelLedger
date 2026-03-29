package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.HouseholdAggregatePreferencesRequest;
import com.playdata.calen.account.dto.HouseholdAggregatePreferencesResponse;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.HouseholdAggregatePreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account/preferences")
@RequiredArgsConstructor
public class AccountPreferenceController {

    private final HouseholdAggregatePreferenceService householdAggregatePreferenceService;

    @GetMapping("/household-aggregates")
    public HouseholdAggregatePreferencesResponse getHouseholdAggregatePreferences(
            @AuthenticationPrincipal AppUserPrincipal currentUser
    ) {
        return householdAggregatePreferenceService.getPreferences(currentUser.userId());
    }

    @PutMapping("/household-aggregates")
    public HouseholdAggregatePreferencesResponse saveHouseholdAggregatePreferences(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody HouseholdAggregatePreferencesRequest request
    ) {
        return householdAggregatePreferenceService.savePreferences(currentUser.userId(), request);
    }
}
