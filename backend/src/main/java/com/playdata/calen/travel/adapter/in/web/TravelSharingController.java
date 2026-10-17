package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.TravelSharingMapper;
import com.playdata.calen.travel.application.port.in.ManageTravelSharingUseCase;
import com.playdata.calen.travel.dto.*;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/travel")
public class TravelSharingController {
    private final ManageTravelSharingUseCase sharing;

    @GetMapping("/share-recipients")
    public List<TravelShareRecipientResponse> search(@AuthenticationPrincipal AppUserPrincipal user,
            @RequestParam(name = "q", defaultValue = "") String query) {
        return sharing.searchRecipients(user.userId(), query).stream().map(TravelSharingMapper::recipient).toList();
    }

    @GetMapping("/share-groups")
    public List<TravelShareGroupResponse> groups(@AuthenticationPrincipal AppUserPrincipal user) {
        return sharing.groups(user.userId()).stream().map(TravelSharingMapper::group).toList();
    }

    @PostMapping("/share-groups")
    public TravelShareGroupResponse saveGroup(@AuthenticationPrincipal AppUserPrincipal user, @Valid @RequestBody TravelShareGroupRequest request) {
        return TravelSharingMapper.group(sharing.saveGroup(user.userId(), request.name(), request.recipientLoginIds()));
    }

    @DeleteMapping("/share-groups/{groupId}")
    public void deleteGroup(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long groupId) {
        sharing.deleteGroup(user.userId(), groupId);
    }

    @PostMapping("/plans/{planId}/shares")
    public TravelPlanShareResponse sharePlan(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long planId,
            @Valid @RequestBody TravelPlanShareRequest request) {
        return TravelSharingMapper.share(sharing.shareCompletedPlan(user.userId(), planId, request.loginId()));
    }

    @GetMapping("/plans/{planId}/shares")
    public List<TravelPlanShareResponse> shares(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long planId) {
        return sharing.shares(user.userId(), planId).stream().map(TravelSharingMapper::share).toList();
    }

    @DeleteMapping("/plans/{planId}/shares/{shareId}")
    public void cancelShare(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long planId, @PathVariable Long shareId) {
        sharing.cancelShare(user.userId(), planId, shareId);
    }

    @PutMapping("/plans/{planId}/public-share")
    public TravelPlanPublicShareResponse publicShare(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long planId,
            @Valid @RequestBody TravelPlanPublicShareRequest request) {
        return TravelSharingMapper.publicShare(sharing.publicShare(user.userId(), planId, request.publicShared()));
    }
}
