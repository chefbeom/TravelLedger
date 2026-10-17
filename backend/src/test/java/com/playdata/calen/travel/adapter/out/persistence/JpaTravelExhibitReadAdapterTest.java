package com.playdata.calen.travel.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.travel.application.port.out.TravelRoutePayloadPort;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.policy.TravelExhibitPolicy;
import com.playdata.calen.travel.repository.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JpaTravelExhibitReadAdapterTest {
    final TravelPlanRepository plans = mock(TravelPlanRepository.class);
    final TravelPlanShareRepository shares = mock(TravelPlanShareRepository.class);
    final TravelBudgetItemRepository budgets = mock(TravelBudgetItemRepository.class);
    final TravelExpenseRecordRepository records = mock(TravelExpenseRecordRepository.class);
    final TravelMediaAssetRepository media = mock(TravelMediaAssetRepository.class);
    final TravelRouteSegmentRepository routes = mock(TravelRouteSegmentRepository.class);
    final JpaTravelExhibitReadAdapter adapter = new JpaTravelExhibitReadAdapter(mock(AppUserService.class), plans, shares, budgets, records, media, routes, mock(TravelRoutePayloadPort.class));

    @Test void publicCommunityPhotoDoesNotInitializePlanVisibility() {
        var asset = mock(TravelMediaAsset.class); var record = mock(TravelExpenseRecord.class);
        when(media.findById(4L)).thenReturn(Optional.of(asset)); when(asset.getRecord()).thenReturn(record);
        when(record.getRecordType()).thenReturn(TravelRecordType.MEMORY); when(asset.getMediaType()).thenReturn(TravelMediaType.PHOTO);
        when(record.getSharedWithCommunity()).thenReturn(true);
        assertThat(TravelExhibitPolicy.publicMedia(adapter.publicMedia(4L).orElseThrow())).isTrue();
        verify(asset, never()).getPlan();
    }

    @Test void publicReceiptIsRejectedWithoutReadingCommunityOrPlanVisibility() {
        var asset = mock(TravelMediaAsset.class); var record = mock(TravelExpenseRecord.class);
        when(media.findById(4L)).thenReturn(Optional.of(asset)); when(asset.getRecord()).thenReturn(record);
        when(record.getRecordType()).thenReturn(TravelRecordType.MEMORY); when(asset.getMediaType()).thenReturn(TravelMediaType.RECEIPT);
        assertThat(TravelExhibitPolicy.publicMedia(adapter.publicMedia(4L).orElseThrow())).isFalse();
        verify(record, never()).getSharedWithCommunity(); verify(asset, never()).getPlan();
    }

    @Test void recipientMediaMetadataDoesNotReadOrFilterTheRecordOrMediaKind() {
        var asset = mock(TravelMediaAsset.class); var plan = mock(TravelPlan.class);
        when(media.findById(4L)).thenReturn(Optional.of(asset)); when(asset.getPlan()).thenReturn(plan); when(plan.getId()).thenReturn(3L);
        when(asset.getContentType()).thenReturn("application/pdf");
        var result = adapter.media(4L).orElseThrow();
        assertThat(result.planId()).isEqualTo(3L); assertThat(result.download().contentType()).isEqualTo("application/pdf");
        verify(asset, never()).getRecord(); verify(asset, never()).getMediaType(); verify(plan, never()).getPublicShared();
    }

    @Test void receivedShareIsRecipientScopedAndDoesNotLoadAttributionBeforeAuthorization() {
        var share = mock(TravelPlanShare.class); var plan = mock(TravelPlan.class); var owner = mock(AppUser.class);
        when(shares.findByIdAndRecipientId(9L, 2L)).thenReturn(Optional.of(share));
        when(share.getId()).thenReturn(9L); when(share.getPlan()).thenReturn(plan); when(plan.getOwner()).thenReturn(owner);
        when(owner.getId()).thenReturn(1L); when(plan.getId()).thenReturn(3L); when(plan.getStatus()).thenReturn(TravelPlanStatus.PLANNED);
        assertThat(adapter.receivedShare(2L, 9L).plan().ownerId()).isEqualTo(1L);
        verify(share, never()).getSharedBy(); verify(owner, never()).getLoginId(); verify(owner, never()).getDisplayName();
        verifyNoInteractions(budgets, records, media, routes);
    }
}
