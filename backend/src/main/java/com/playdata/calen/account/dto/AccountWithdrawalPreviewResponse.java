package com.playdata.calen.account.dto;

import com.playdata.calen.familyalbum.service.FamilyAlbumWithdrawalService.TransferChoice;
import com.playdata.calen.travel.service.TravelShareGroupWithdrawalService;
import com.playdata.calen.travel.service.TravelPlanWithdrawalService;
import java.util.List;

/** Informational only; no withdrawal or ownership transfer has been performed. */
public record AccountWithdrawalPreviewResponse(
        boolean withdrawalAvailable,
        boolean kakaoLinked,
        boolean kakaoUnlinkConfigured,
        List<TransferChoice> requiredFamilyTransfers,
        List<TravelShareGroupWithdrawalService.TransferChoice> requiredShareGroupTransfers,
        List<TravelPlanWithdrawalService.TransferChoice> requiredTravelPlanTransfers,
        TravelPlanWithdrawalService.ForeignMediaSummary foreignTravelMedia
) {}
