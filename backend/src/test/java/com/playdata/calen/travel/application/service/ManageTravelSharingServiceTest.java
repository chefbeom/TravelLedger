package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.TravelSharingStore;
import com.playdata.calen.travel.application.port.out.TravelSummaryInvalidationPort;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.domain.model.TravelSharingData.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;

class ManageTravelSharingServiceTest {
    final TravelSharingStore store = mock(TravelSharingStore.class);
    final TravelSummaryInvalidationPort cache = mock(TravelSummaryInvalidationPort.class);
    final ManageTravelSharingService sharing = new ManageTravelSharingService(store, cache);
    final Recipient owner = new Recipient(1L, "owner", "Owner");
    final Recipient recipient = new Recipient(2L, "canonical", "Recipient");
    final Share share = new Share(9L, 3L, "trip", "canonical", "Recipient", LocalDateTime.of(2026, 10, 16, 12, 0));

    void completed() {
        when(store.activeOwner(1L)).thenReturn(owner);
        when(store.ownedPlan(1L, 3L)).thenReturn(new Plan(3L, TravelPlanStatus.COMPLETED));
        when(store.activeRecipient("recipient")).thenReturn(Optional.of(recipient));
        when(store.share(9L)).thenReturn(share);
    }

    @Test void shareKeepsActiveOwnerOwnershipStatusRecipientCreateCacheThenSnapshotOrder() {
        completed();
        when(store.createShare(1L, 3L, 2L)).thenReturn(9L);
        assertThat(sharing.shareCompletedPlan(1L, 3L, " recipient ")).isSameAs(share);
        var order = inOrder(store, cache);
        order.verify(store).activeOwner(1L);
        order.verify(store).ownedPlan(1L, 3L);
        order.verify(store).activeRecipient("recipient");
        order.verify(store).findShare(3L, 2L);
        order.verify(store).createShare(1L, 3L, 2L);
        order.verify(cache).invalidatePublic();
        order.verify(store).share(9L);
        order.verifyNoMoreInteractions();
    }

    @Test void repeatedShareKeepsTheExistingRowAndStillInvalidatesPublicBeforeReadingItsSnapshot() {
        completed(); when(store.findShare(3L, 2L)).thenReturn(Optional.of(9L));
        assertThat(sharing.shareCompletedPlan(1L, 3L, "recipient")).isSameAs(share);
        verify(store, never()).createShare(any(), any(), any());
        var order = inOrder(cache, store);
        order.verify(cache).invalidatePublic(); order.verify(store).share(9L);
    }

    @ParameterizedTest @NullSource @EnumSource(value = TravelPlanStatus.class, names = {"PLANNED", "SAMPLE"})
    void incompleteAndNullStatusFailBeforeRecipientValidationOrAnyCacheChange(TravelPlanStatus status) {
        when(store.activeOwner(1L)).thenReturn(owner);
        when(store.ownedPlan(1L, 3L)).thenReturn(new Plan(3L, status));
        assertThatThrownBy(() -> sharing.shareCompletedPlan(1L, 3L, " "))
                .hasMessage("완성된 여행만 다른 사용자에게 공유할 수 있습니다.");
        verify(store, never()).activeRecipient(any()); verifyNoInteractions(cache);
    }

    @Test void ownerFailurePrecedesPlanLookupAndPlanFailurePrecedesRecipientLookup() {
        when(store.activeOwner(1L)).thenThrow(new NotFoundException("missing owner"));
        assertThatThrownBy(() -> sharing.shareCompletedPlan(1L, 3L, "recipient")).hasMessage("missing owner");
        verify(store, never()).ownedPlan(any(), any());
        doReturn(owner).when(store).activeOwner(1L);
        when(store.ownedPlan(1L, 3L)).thenThrow(new NotFoundException("Travel plan not found."));
        assertThatThrownBy(() -> sharing.shareCompletedPlan(1L, 3L, "recipient")).hasMessage("Travel plan not found.");
        verify(store, never()).activeRecipient(any()); verifyNoInteractions(cache);
    }

    @Test void blankMissingAndSelfRecipientsHaveTheSameFailuresWithoutCreatingAnyShare() {
        completed();
        assertThatThrownBy(() -> sharing.shareCompletedPlan(1L, 3L, null)).hasMessage("공유할 사용자 아이디를 입력해주세요.");
        assertThatThrownBy(() -> sharing.shareCompletedPlan(1L, 3L, "missing")).hasMessage("공유할 사용자를 찾을 수 없습니다.");
        when(store.activeRecipient("owner")).thenReturn(Optional.of(owner));
        assertThatThrownBy(() -> sharing.shareCompletedPlan(1L, 3L, "owner")).hasMessage("자기 자신에게는 여행 전시를 공유할 수 없습니다.");
        verify(store, never()).findShare(any(), any()); verifyNoInteractions(cache);
    }

    @Test void listAndCancelCheckTheOwnerPlanWithoutAddingAnActiveAccountGuard() {
        when(store.shares(1L, 3L)).thenReturn(List.of(share));
        assertThat(sharing.shares(1L, 3L)).containsExactly(share);
        sharing.cancelShare(1L, 3L, 9L);
        var order = inOrder(store, cache);
        order.verify(store).ownedPlan(1L, 3L); order.verify(store).shares(1L, 3L);
        order.verify(store).ownedPlan(1L, 3L); order.verify(store).cancelShare(1L, 3L, 9L); order.verify(cache).invalidatePublic();
        verify(store, never()).activeOwner(any());
    }

    @Test void cancelFailureDoesNotInvalidateAnything() {
        doThrow(new NotFoundException("공유 내역을 찾을 수 없습니다.")).when(store).cancelShare(1L, 3L, 9L);
        assertThatThrownBy(() -> sharing.cancelShare(1L, 3L, 9L)).hasMessage("공유 내역을 찾을 수 없습니다.");
        verifyNoInteractions(cache);
    }

    @Test void searchAndGroupsKeepActiveOwnerFirstAndTheExistingSearchLimitAndRawQuery() {
        when(store.searchRecipients(1L, " query ", 10)).thenReturn(List.of(recipient));
        when(store.groups(1L)).thenReturn(List.of());
        assertThat(sharing.searchRecipients(1L, " query ")).containsExactly(recipient);
        assertThat(sharing.groups(1L)).isEmpty();
        var order = inOrder(store);
        order.verify(store).activeOwner(1L); order.verify(store).searchRecipients(1L, " query ", 10);
        order.verify(store).activeOwner(1L); order.verify(store).groups(1L);
        verifyNoInteractions(cache);
    }

    @Test void groupResolvesEveryNonblankInputThenDeduplicatesByCanonicalLoginInFirstSeenOrder() {
        completed();
        var third = new Recipient(4L, "third", "Third");
        when(store.activeRecipient("alias")).thenReturn(Optional.of(recipient));
        when(store.activeRecipient("third")).thenReturn(Optional.of(third));
        var group = new Group(5L, "Family", List.of(recipient, third), share.sharedAt(), share.sharedAt());
        when(store.replaceGroup(1L, "Family", List.of(recipient, third))).thenReturn(group);
        assertThat(sharing.saveGroup(1L, " Family ", Arrays.asList(null, " ", " recipient ", "third", "alias", "recipient"))).isSameAs(group);
        var order = inOrder(store);
        order.verify(store).activeOwner(1L); order.verify(store).activeRecipient("recipient");
        order.verify(store).activeRecipient("third"); order.verify(store).activeRecipient("alias"); order.verify(store).activeRecipient("recipient");
        order.verify(store).replaceGroup(1L, "Family", List.of(recipient, third));
        verifyNoInteractions(cache);
    }

    @Test void groupNameAndEmptyRecipientsHaveExistingValidationOrderAndMessages() {
        when(store.activeOwner(1L)).thenReturn(owner);
        assertThatThrownBy(() -> sharing.saveGroup(1L, " ", List.of("missing"))).hasMessage("공유 그룹 이름을 입력해주세요.");
        verify(store, never()).activeRecipient(any());
        assertThatThrownBy(() -> sharing.saveGroup(1L, "Family", null)).hasMessage("공유 그룹에 포함할 사용자를 선택해주세요.");
        assertThatThrownBy(() -> sharing.saveGroup(1L, "Family", Arrays.asList(null, " "))).hasMessage("공유 그룹에 포함할 사용자를 선택해주세요.");
        verify(store, never()).replaceGroup(any(), any(), any()); verifyNoInteractions(cache);
    }

    @Test void groupRejectsMissingOrSelfMemberBeforeReplacingExistingMembership() {
        completed();
        assertThatThrownBy(() -> sharing.saveGroup(1L, "Family", List.of("recipient", " missing ")))
                .hasMessage("공유 그룹 사용자를 찾을 수 없습니다: missing");
        when(store.activeRecipient("owner")).thenReturn(Optional.of(owner));
        assertThatThrownBy(() -> sharing.saveGroup(1L, "Family", List.of("owner"))).hasMessage("자기 자신은 공유 그룹에 넣을 수 없습니다.");
        verify(store, never()).replaceGroup(any(), any(), any()); verifyNoInteractions(cache);
    }

    @Test void groupDeletionChecksActiveOwnerAndDoesNotInvalidateTravelSummaries() {
        sharing.deleteGroup(1L, 5L);
        var order = inOrder(store); order.verify(store).activeOwner(1L); order.verify(store).deleteGroup(1L, 5L);
        verifyNoInteractions(cache);
    }

    @Test void publicEnableAndDisablePreserveBothCacheCallsBeforeResponseSnapshot() {
        when(store.ownedPlan(1L, 3L)).thenReturn(new Plan(3L, TravelPlanStatus.COMPLETED));
        sharing.publicShare(1L, 3L, true);
        var order = inOrder(store, cache);
        order.verify(store).ownedPlan(1L, 3L); order.verify(store).setPublicShare(3L, true);
        order.verify(cache).invalidateOwner(1L); order.verify(cache).invalidatePublic(); order.verify(store).publicShare(3L);
        verify(store, never()).activeOwner(any());
        when(store.ownedPlan(1L, 3L)).thenReturn(new Plan(3L, null));
        sharing.publicShare(1L, 3L, null);
        verify(store).setPublicShare(3L, false);
    }

    @Test void incompletePublicEnableDoesNotMutateOrEvictAndSnapshotFailureHappensAfterEviction() {
        when(store.ownedPlan(1L, 3L)).thenReturn(new Plan(3L, TravelPlanStatus.PLANNED));
        assertThatThrownBy(() -> sharing.publicShare(1L, 3L, true)).hasMessage("완성된 여행만 다른 사용자에게 공유할 수 있습니다.");
        verify(store, never()).setPublicShare(any(), anyBoolean()); verifyNoInteractions(cache);
        when(store.ownedPlan(1L, 3L)).thenReturn(new Plan(3L, TravelPlanStatus.COMPLETED));
        when(store.publicShare(3L)).thenThrow(new IllegalStateException("synthetic snapshot"));
        assertThatThrownBy(() -> sharing.publicShare(1L, 3L, true)).hasMessage("synthetic snapshot");
        var order = inOrder(cache, store);
        order.verify(cache).invalidateOwner(1L); order.verify(cache).invalidatePublic(); order.verify(store).publicShare(3L);
    }
}
