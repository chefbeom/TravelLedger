package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.HouseholdAggregatePreferencesRequest;
import com.playdata.calen.account.dto.HouseholdAggregatePreferencesResponse;
import com.playdata.calen.account.dto.UserLayoutSettingRequest;
import com.playdata.calen.account.dto.UserLayoutSettingResponse;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.HouseholdAggregatePreferenceService;
import com.playdata.calen.account.service.UserLayoutSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account/preferences")
@RequiredArgsConstructor
public class AccountPreferenceController {

    private final HouseholdAggregatePreferenceService householdAggregatePreferenceService;
    private final UserLayoutSettingService userLayoutSettingService;

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

    @GetMapping("/layout-settings/{scope}")
    public UserLayoutSettingResponse getLayoutSetting(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable String scope
    ) {
        return userLayoutSettingService.getLayoutSetting(currentUser.userId(), scope);
    }

    @PutMapping("/layout-settings/{scope}")
    public UserLayoutSettingResponse saveLayoutSetting(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable String scope,
            @RequestBody UserLayoutSettingRequest request
    ) {
        return userLayoutSettingService.saveLayoutSetting(currentUser.userId(), scope, request);
    }
}
