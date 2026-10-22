package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.HouseholdAggregatePreferencesRequest;
import com.playdata.calen.account.dto.HouseholdAggregatePreferencesResponse;
import com.playdata.calen.account.dto.HouseholdGoalRequest;
import com.playdata.calen.account.dto.HouseholdGoalResponse;
import com.playdata.calen.account.dto.HouseholdPersonalizationRequest;
import com.playdata.calen.account.dto.HouseholdPersonalizationResponse;
import com.playdata.calen.account.dto.UserLayoutSettingRequest;
import com.playdata.calen.account.dto.UserLayoutSettingResponse;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.HouseholdAggregatePreferenceService;
import com.playdata.calen.account.service.HouseholdGoalService;
import com.playdata.calen.account.service.HouseholdPersonalizationService;
import com.playdata.calen.account.service.UserLayoutSettingService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account/preferences")
@RequiredArgsConstructor
public class AccountPreferenceController {

    private final HouseholdGoalService householdGoalService;
    private final HouseholdAggregatePreferenceService householdAggregatePreferenceService;
    private final UserLayoutSettingService userLayoutSettingService;
    private final HouseholdPersonalizationService householdPersonalizationService;

    @GetMapping("/household-personalization")
    public HouseholdPersonalizationResponse getHouseholdPersonalization(
            @AuthenticationPrincipal AppUserPrincipal currentUser) {
        return householdPersonalizationService.get(currentUser.userId());
    }

    @PutMapping("/household-personalization")
    public HouseholdPersonalizationResponse saveHouseholdPersonalization(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody HouseholdPersonalizationRequest request) {
        return householdPersonalizationService.save(currentUser.userId(), request);
    }

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
        return householdPersonalizationService.saveCompatibleWidgets(currentUser.userId(), request);
    }


    @GetMapping("/household-goals")
    public List<HouseholdGoalResponse> getHouseholdGoals(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(defaultValue = "false") boolean includeArchived
    ) {
        return householdGoalService.getGoals(currentUser.userId(), includeArchived);
    }

    @PostMapping("/household-goals")
    public HouseholdGoalResponse createHouseholdGoal(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody HouseholdGoalRequest request
    ) {
        return householdGoalService.createGoal(currentUser.userId(), request);
    }

    @PutMapping("/household-goals/{goalId}")
    public HouseholdGoalResponse updateHouseholdGoal(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long goalId,
            @Valid @RequestBody HouseholdGoalRequest request
    ) {
        return householdGoalService.updateGoal(currentUser.userId(), goalId, request);
    }

    @DeleteMapping("/household-goals/{goalId}")
    public HouseholdGoalResponse archiveHouseholdGoal(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long goalId
    ) {
        return householdGoalService.archiveGoal(currentUser.userId(), goalId);
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
        if ("household-calendar".equals(scope) || "household-calendar-view".equals(scope)) {
            return householdPersonalizationService.saveCompatibleLayout(currentUser.userId(), scope, request);
        }
        return userLayoutSettingService.saveLayoutSetting(currentUser.userId(), scope, request);
    }
}
