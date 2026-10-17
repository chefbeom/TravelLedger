package com.playdata.calen.sharing.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.sharing.application.port.in.CreateRecordShareCommand;
import com.playdata.calen.sharing.application.port.out.*;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.RecordShareStatus;
import com.playdata.calen.sharing.domain.model.ShareCreationData.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateRecordShareServiceTest {
    @Mock ShareParticipantsPort participants;
    @Mock ShareSourcePort sources;
    @Mock ShareRequestsPort requests;
    CreateRecordShareService service;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-15T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    final Content content = new Content("공유할 거래", null, "current-content");
    final Actor sender = new Actor(1L, "보낸 사람"), recipient = new Actor(2L, "받는 사람");

    @BeforeEach void setup() { service = new CreateRecordShareService(participants, sources, requests, clock); }

    @Test void deduplicatesRecipientsAndHoldsSourceBeforeRequestLock() {
        ready();
        when(requests.savePending(isNull(), any())).thenReturn(receipt());
        var result = service.create(1L, command(null, "  함께 쓴 식비  ", List.of(2L, 2L)));
        assertThat(result).containsExactly(receipt());
        var order = inOrder(participants, sources, requests);
        order.verify(participants).requireActiveActor(1L);
        order.verify(participants).requireGroupAccess(5L, 1L);
        order.verify(sources).lockOwnedSource(1L, RecordShareKind.LEDGER, 100L, null);
        order.verify(participants).requireGroupAccess(5L, 2L);
        order.verify(participants).requireActiveActor(2L);
        order.verify(requests).lockExisting(RecordShareKind.LEDGER, 100L, 2L);
        var submission = ArgumentCaptor.forClass(Submission.class);
        order.verify(requests).savePending(isNull(), submission.capture());
        order.verifyNoMoreInteractions();
        assertThat(submission.getValue().shareMemo()).isEqualTo("함께 쓴 식비");
        assertThat(submission.getValue().createdAt()).isEqualTo(LocalDateTime.of(2026, 10, 15, 12, 0));
        assertThat(submission.getValue().content()).isSameAs(content);
    }

    @Test void rejectsEditedTravelAndOversizedMemoBeforeAccessingAnyPort() {
        var travel = new CreateRecordShareCommand(5L, RecordShareKind.TRAVEL, 100L, List.of(2L), null, draft());
        assertThatThrownBy(() -> service.create(1L, travel)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(1L, command(null, "가".repeat(501), List.of(2L)))).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(participants, sources, requests);
    }

    @Test void selfRecipientNeverCreatesARequest() {
        when(participants.requireActiveActor(1L)).thenReturn(sender);
        when(participants.requireGroupAccess(5L, 1L)).thenReturn(new Group(5L, "가족"));
        when(sources.lockOwnedSource(1L, RecordShareKind.LEDGER, 100L, null)).thenReturn(content);
        assertThatThrownBy(() -> service.create(1L, command(null, null, List.of(1L)))).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(requests);
    }

    @Test void unchangedPendingRequestIsReusedWithoutReplacingItsMemo() {
        ready();
        when(requests.lockExisting(RecordShareKind.LEDGER, 100L, 2L)).thenReturn(Optional.of(new Existing(9L, 5L, RecordShareStatus.PENDING, "old-content")));
        when(requests.readLocked(9L)).thenReturn(receipt());
        assertThat(service.create(1L, command(null, "새 메모", List.of(2L)))).containsExactly(receipt());
        verify(requests, never()).savePending(any(), any());
    }

    @Test void editedRetryIsReusedOnlyWhenContentIsIdentical() {
        ready(draft());
        when(requests.lockExisting(RecordShareKind.LEDGER, 100L, 2L)).thenReturn(Optional.of(new Existing(9L, 5L, RecordShareStatus.PENDING, content.fingerprint())));
        when(requests.readLocked(9L)).thenReturn(receipt());
        assertThat(service.create(1L, command(draft(), null, List.of(2L)))).containsExactly(receipt());
        verify(requests, never()).savePending(any(), any());
    }

    @Test void acceptedLedgerIsNotOverwrittenEvenInAnotherGroup() {
        ready(draft());
        when(requests.lockExisting(RecordShareKind.LEDGER, 100L, 2L)).thenReturn(Optional.of(new Existing(9L, 6L, RecordShareStatus.ACCEPTED, "different-content")));
        assertThatThrownBy(() -> service.create(1L, command(draft(), null, List.of(2L))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("이미 상대방 가계부에 등록");
        verify(requests, never()).savePending(any(), any());
        verify(requests, never()).readLocked(anyLong());
    }

    @Test void canceledRequestCanBeReplacedUsingItsExistingIdentity() {
        ready();
        when(requests.lockExisting(RecordShareKind.LEDGER, 100L, 2L)).thenReturn(Optional.of(new Existing(9L, 5L, RecordShareStatus.CANCELED, "old-content")));
        when(requests.savePending(eq(9L), any())).thenReturn(receipt());
        service.create(1L, command(null, " \n ", List.of(2L)));
        var submission = ArgumentCaptor.forClass(Submission.class);
        verify(requests).savePending(eq(9L), submission.capture());
        assertThat(submission.getValue().shareMemo()).isNull();
    }

    @Test void missingOrUnownedSourceStopsBeforeAnyRequestAccess() {
        when(participants.requireActiveActor(1L)).thenReturn(sender);
        when(participants.requireGroupAccess(5L, 1L)).thenReturn(new Group(5L, "가족"));
        when(sources.lockOwnedSource(1L, RecordShareKind.LEDGER, 100L, null)).thenThrow(new NotFoundException("missing"));
        assertThatThrownBy(() -> service.create(1L, command(null, null, List.of(2L)))).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(requests);
    }

    @Test void commandDoesNotRetainAMutableRecipientList() {
        var original = new ArrayList<>(List.of(2L));
        var command = command(null, null, original);
        original.add(3L);
        assertThat(command.recipientIds()).containsExactly(2L);
        assertThatThrownBy(() -> command.recipientIds().add(4L)).isInstanceOf(UnsupportedOperationException.class);
    }

    private void ready() { ready(null); }
    private void ready(LedgerDraft override) {
        when(participants.requireActiveActor(1L)).thenReturn(sender);
        when(participants.requireGroupAccess(5L, 1L)).thenReturn(new Group(5L, "가족"));
        when(sources.lockOwnedSource(1L, RecordShareKind.LEDGER, 100L, override)).thenReturn(content);
        when(participants.requireActiveActor(2L)).thenReturn(recipient);
    }
    private CreateRecordShareCommand command(LedgerDraft override, String memo, List<Long> ids) {
        return new CreateRecordShareCommand(5L, RecordShareKind.LEDGER, 100L, ids, memo, override);
    }
    private LedgerDraft draft() { return new LedgerDraft(null, null, "공유 수정", null, null, null, null, null, "EXPENSE", 1L, null, 2L, null, null); }
    private Receipt receipt() { return new Receipt(9L, 5L, "가족", RecordShareKind.LEDGER, 100L, sender, recipient,
            RecordShareStatus.PENDING, "공유할 거래", null, null, LocalDateTime.now(clock), null, "이전 메모"); }
}
