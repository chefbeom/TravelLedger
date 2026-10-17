package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelExhibitData.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.Download;
import com.playdata.calen.travel.domain.policy.TravelExhibitPolicy;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReadTravelExhibitsServiceTest {
    final TravelExhibitReadStore store = mock(TravelExhibitReadStore.class);
    final TravelPublicMediaTokenPort tokens = mock(TravelPublicMediaTokenPort.class);
    final ReadTravelExhibitsService reads = new ReadTravelExhibitsService(store, tokens);
    final Download download = new Download("test-only/photo.jpg", "image/jpeg", "photo.jpg");
    final Attribution attribution = new Attribution("synthetic-owner", "Owner", LocalDateTime.of(2026, 10, 16, 12, 0));

    Access access(TravelPlanStatus status) { return new Access(9L, new Plan(3L, 1L, status)); }

    @Test void invalidTokenFailsBeforeAnyDatabaseOrAccountLookup() {
        assertThatThrownBy(() -> reads.publicMedia(4L, "invalid")).hasMessage("Shared media not found.");
        verify(tokens).matches(4L, "invalid"); verifyNoInteractions(store);
    }

    @Test void validTokenIsNotSufficientForPrivatePhotosOrReceipts() {
        when(tokens.matches(4L, "test-token")).thenReturn(true);
        when(store.publicMedia(4L)).thenReturn(Optional.of(new PublicMedia(TravelRecordType.MEMORY, TravelMediaType.PHOTO, false, false, TravelPlanStatus.COMPLETED, download)));
        assertThatThrownBy(() -> reads.publicMedia(4L, "test-token")).hasMessage("Shared media not found.");
        when(store.publicMedia(4L)).thenReturn(Optional.of(new PublicMedia(TravelRecordType.MEMORY, TravelMediaType.RECEIPT, true, true, TravelPlanStatus.COMPLETED, download)));
        assertThatThrownBy(() -> reads.publicMedia(4L, "test-token")).hasMessage("Shared media not found.");
        when(store.publicMedia(4L)).thenReturn(Optional.of(new PublicMedia(TravelRecordType.MEMORY, TravelMediaType.PHOTO, true, false, TravelPlanStatus.PLANNED, download)));
        assertThat(reads.publicMedia(4L, "test-token")).isSameAs(download);
        verify(store, never()).requireActiveViewer(any()); verify(store, never()).media(any());
    }

    @Test void publicPolicyMatchesTheOriginalShortCircuitTruthTableIncludingNulls() {
        List<TravelRecordType> records = Arrays.asList(null, TravelRecordType.LEDGER, TravelRecordType.MEMORY);
        List<TravelMediaType> mediaTypes = Arrays.asList(null, TravelMediaType.PHOTO, TravelMediaType.RECEIPT);
        List<TravelPlanStatus> statuses = Arrays.asList(null, TravelPlanStatus.PLANNED, TravelPlanStatus.SAMPLE, TravelPlanStatus.COMPLETED);
        for (var record : records) for (var media : mediaTypes) for (var status : statuses) {
            for (boolean community : List.of(false, true)) for (boolean publicPlan : List.of(false, true)) {
                boolean original = record == TravelRecordType.MEMORY && media == TravelMediaType.PHOTO
                        && (community || (publicPlan && status == TravelPlanStatus.COMPLETED));
                assertThat(TravelExhibitPolicy.publicMedia(new PublicMedia(record, media, community, publicPlan, status, download)))
                        .as("record=%s media=%s status=%s community=%s public=%s", record, media, status, community, publicPlan).isEqualTo(original);
            }
        }
    }

    @Test void receivedListKeepsActiveViewerBeforePagingAndCompletedOnlyLookup() {
        var page = new Page(List.of(), 0, 20, 0, 0);
        when(store.completedShares(2L, 0, 20)).thenReturn(page);
        assertThat(reads.received(2L, -1, Integer.MAX_VALUE)).isSameAs(page);
        var order = inOrder(store); order.verify(store).requireActiveViewer(2L); order.verify(store).completedShares(2L, 0, 20);
        assertThat(TravelExhibitPolicy.page(null)).isZero(); assertThat(TravelExhibitPolicy.page(Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(TravelExhibitPolicy.size(null)).isEqualTo(10); assertThat(TravelExhibitPolicy.size(0)).isEqualTo(1);
        verifyNoInteractions(tokens);
    }

    @Test void missingOrInactiveViewerStopsBeforeReadingAShare() {
        doThrow(new NotFoundException("missing viewer")).when(store).requireActiveViewer(2L);
        assertThatThrownBy(() -> reads.receivedDetail(2L, 9L)).hasMessage("missing viewer");
        assertThatThrownBy(() -> reads.receivedMedia(2L, 9L, 4L)).hasMessage("missing viewer");
        verify(store, never()).receivedShare(any(), any()); verifyNoInteractions(tokens);
    }

    @Test void receivedDetailChecksCompletionBeforeReadingChildrenAndAttribution() {
        for (var status : Arrays.asList(null, TravelPlanStatus.PLANNED, TravelPlanStatus.SAMPLE)) {
            when(store.receivedShare(2L, 9L)).thenReturn(access(status));
            assertThatThrownBy(() -> reads.receivedDetail(2L, 9L)).hasMessage("Shared travel exhibit was not found.");
        }
        verify(store, never()).planDetail(any(), any()); verify(store, never()).shareAttribution(any());
        when(store.receivedShare(2L, 9L)).thenReturn(access(TravelPlanStatus.COMPLETED));
        when(store.shareAttribution(9L)).thenReturn(attribution);
        assertThat(reads.receivedDetail(2L, 9L).sharedBy()).isSameAs(attribution);
        var order = inOrder(store);
        order.verify(store).planDetail(1L, 3L); order.verify(store).shareAttribution(9L);
    }

    @Test void receivedMediaChecksShareAndCompletionBeforeTheMediaRowAndNeverUsesTheOwnedCache() {
        when(store.receivedShare(2L, 9L)).thenReturn(access(TravelPlanStatus.PLANNED));
        assertThatThrownBy(() -> reads.receivedMedia(2L, 9L, 4L)).hasMessage("Shared exhibit media not found.");
        verify(store, never()).media(any());
        when(store.receivedShare(2L, 9L)).thenReturn(access(TravelPlanStatus.COMPLETED));
        when(store.media(4L)).thenReturn(Optional.of(new Media(33L, download)));
        assertThatThrownBy(() -> reads.receivedMedia(2L, 9L, 4L)).hasMessage("Shared exhibit media not found.");
        var receipt = new Download("test-only/receipt.pdf", "application/pdf", "receipt.pdf");
        when(store.media(4L)).thenReturn(Optional.of(new Media(3L, receipt)));
        assertThat(reads.receivedMedia(2L, 9L, 4L)).isSameAs(receipt);
        verify(store, never()).publicMedia(any()); verifyNoInteractions(tokens);
    }

    @Test void trustedRecordShareBridgeKeepsOwnerScopeWithoutAddingCompletionOrViewerRules() {
        when(store.ownedPlan(1L, 3L)).thenReturn(new Plan(3L, 1L, TravelPlanStatus.PLANNED));
        when(store.ownerAttribution(3L)).thenReturn(new Attribution("owner", "Owner", null));
        when(store.media(4L)).thenReturn(Optional.of(new Media(3L, download)));
        assertThat(reads.recordShareDetail(1L, 3L, 19L).id()).isEqualTo(19L);
        assertThat(reads.recordShareMedia(1L, 3L, 4L)).isSameAs(download);
        var order = inOrder(store);
        order.verify(store).ownedPlan(1L, 3L); order.verify(store).planDetail(1L, 3L); order.verify(store).ownerAttribution(3L);
        order.verify(store).ownedPlan(1L, 3L); order.verify(store).media(4L);
        verify(store, never()).requireActiveViewer(any()); verify(store, never()).receivedShare(any(), any()); verifyNoInteractions(tokens);
    }

    @Test void trustedBridgeRejectsWrongPlanAndMissingMediaWithTheExistingMessage() {
        when(store.media(4L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reads.recordShareMedia(1L, 3L, 4L)).hasMessage("Shared travel media not found.");
        when(store.media(4L)).thenReturn(Optional.of(new Media(33L, download)));
        assertThatThrownBy(() -> reads.recordShareMedia(1L, 3L, 4L)).hasMessage("Shared travel media not found.");
    }
}
