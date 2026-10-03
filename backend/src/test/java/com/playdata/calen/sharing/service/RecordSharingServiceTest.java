package com.playdata.calen.sharing.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.service.LedgerEntryService;
import com.playdata.calen.sharing.domain.*;
import com.playdata.calen.sharing.dto.RecordShareDtos;
import com.playdata.calen.sharing.repository.RecordShareRepository;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.TravelService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecordSharingServiceTest {
    @Mock RecordShareRepository shares;
    @Mock TravelShareGroupRepository groups;
    @Mock TravelShareGroupMemberRepository members;
    @Mock AppUserService users;
    @Mock LedgerEntryService ledger;
    @Mock TravelService travel;
    @Mock EntityManager entityManager;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    RecordSharingService service;
    AppUser sender, recipient;
    TravelShareGroup group;

    @BeforeEach void setup() {
        service = new RecordSharingService(shares, groups, members, users, ledger, travel, mapper, entityManager);
        sender = user(1L); recipient = user(2L);
        group = new TravelShareGroup(); group.setId(5L); group.setOwner(sender); group.setName("가족");
        lenient().when(users.getRequiredUser(1L)).thenReturn(sender);
        lenient().when(users.getRequiredUser(2L)).thenReturn(recipient);
        lenient().when(groups.findById(5L)).thenReturn(Optional.of(group));
        lenient().when(members.existsByGroupIdAndMemberId(5L, 2L)).thenReturn(true);
    }

    @Test void sharingOnlyQueuesSnapshotAndDoesNotCreateRecipientLedger() throws Exception {
        LedgerEntry source = source(sender);
        when(entityManager.find(LedgerEntry.class, 100L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(source);
        when(ledger.getEntryForSharing(1L, 100L)).thenReturn(snapshot());
        when(shares.save(any())).thenAnswer(call -> { RecordShare share = call.getArgument(0); share.setId(9L); return share; });

        var response = service.create(1L, new RecordShareDtos.Create(5L, RecordShareKind.LEDGER, 100L, List.of(2L, 2L)));

        assertThat(response).hasSize(1);
        assertThat(response.get(0).status()).isEqualTo(RecordShareStatus.PENDING);
        assertThat(response.get(0).ledger().title()).isEqualTo("공유 시점 기록");
        assertThat(response.get(0).shareMemo()).isNull();
        verify(ledger, never()).create(anyLong(), any());
        verify(shares).save(any());
    }

    @Test void storesShareMemoForEachSelectedRecipientSeparatelyFromTheLedgerSnapshot() throws Exception {
        when(entityManager.find(LedgerEntry.class, 100L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(source(sender));
        when(ledger.getEntryForSharing(1L, 100L)).thenReturn(snapshot());
        when(users.getRequiredUser(3L)).thenReturn(user(3L));
        when(members.existsByGroupIdAndMemberId(5L, 3L)).thenReturn(true);
        when(shares.save(any())).thenAnswer(call -> { RecordShare share = call.getArgument(0); share.setId(share.getRecipient().getId() + 10); return share; });

        var response = service.create(1L, new RecordShareDtos.Create(5L, RecordShareKind.LEDGER, 100L, List.of(2L, 3L),
                "  당신 카드로 결제했어요.\n식비로 등록해 주세요.  "));

        assertThat(response).hasSize(2).allSatisfy(item -> {
            assertThat(item.shareMemo()).isEqualTo("당신 카드로 결제했어요.\n식비로 등록해 주세요.");
            assertThat(item.ledger().memo()).isEqualTo("메모");
        });
        var captor = ArgumentCaptor.forClass(RecordShare.class);
        verify(shares, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(item -> item.getRecipient().getId()).containsExactly(2L, 3L);
        assertThat(captor.getAllValues()).extracting(RecordShare::getShareMemo).containsOnly("당신 카드로 결제했어요.\n식비로 등록해 주세요.");
        verify(ledger, never()).create(anyLong(), any());
    }

    @Test void blankMemoIsOptionalAndStoredAsNull() throws Exception {
        when(entityManager.find(LedgerEntry.class, 100L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(source(sender));
        when(ledger.getEntryForSharing(1L, 100L)).thenReturn(snapshot());
        when(shares.save(any())).thenAnswer(call -> call.getArgument(0));
        var response = service.create(1L, new RecordShareDtos.Create(5L, RecordShareKind.LEDGER, 100L, List.of(2L), " \n\t "));
        assertThat(response.get(0).shareMemo()).isNull();
    }

    @Test void rejectsOversizedShareMemoBeforeAnyWrite() {
        assertThatThrownBy(() -> service.create(1L, new RecordShareDtos.Create(5L, RecordShareKind.LEDGER, 100L, List.of(2L), "가".repeat(501))))
                .isInstanceOf(BadRequestException.class).hasMessage("공유 메모는 500자까지 입력할 수 있습니다.");
        verifyNoInteractions(entityManager, shares, ledger);
    }

    @Test void retryDoesNotOverwritePendingShareMemoOrSnapshot() throws Exception {
        RecordShare pending = share(RecordShareKind.LEDGER);
        pending.setShareMemo("먼저 남긴 메모");
        pending.setSnapshotJson(mapper.writeValueAsString(snapshot()));
        when(entityManager.find(LedgerEntry.class, 100L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(source(sender));
        when(ledger.getEntryForSharing(1L, 100L)).thenReturn(snapshot());
        when(shares.findLockedByKindAndSourceIdAndRecipientId(RecordShareKind.LEDGER, 100L, 2L)).thenReturn(Optional.of(pending));

        var response = service.create(1L, new RecordShareDtos.Create(5L, RecordShareKind.LEDGER, 100L, List.of(2L), "새 메모"));
        assertThat(response.get(0).shareMemo()).isEqualTo("먼저 남긴 메모");
        verify(shares, never()).save(any());
    }

    @Test void reSharingCanceledRequestReplacesItsMemoIncludingClearingAnOldMemo() throws Exception {
        RecordShare canceled = share(RecordShareKind.LEDGER);
        canceled.setStatus(RecordShareStatus.CANCELED);
        canceled.setShareMemo("이전 요청의 메모");
        when(entityManager.find(LedgerEntry.class, 100L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(source(sender));
        when(ledger.getEntryForSharing(1L, 100L)).thenReturn(snapshot());
        when(shares.findLockedByKindAndSourceIdAndRecipientId(RecordShareKind.LEDGER, 100L, 2L)).thenReturn(Optional.of(canceled));
        when(shares.save(canceled)).thenReturn(canceled);

        var first = service.create(1L, new RecordShareDtos.Create(5L, RecordShareKind.LEDGER, 100L, List.of(2L), "새 요청의 메모"));
        assertThat(first.get(0).shareMemo()).isEqualTo("새 요청의 메모");
        canceled.setStatus(RecordShareStatus.REJECTED);
        var second = service.create(1L, new RecordShareDtos.Create(5L, RecordShareKind.LEDGER, 100L, List.of(2L)));
        assertThat(second.get(0).shareMemo()).isNull();
        assertThat(second.get(0).status()).isEqualTo(RecordShareStatus.PENDING);
    }

    @Test void rejectsSomeoneElsesSource() {
        when(entityManager.find(LedgerEntry.class, 100L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(source(recipient));
        assertThatThrownBy(() -> service.create(1L, new RecordShareDtos.Create(5L, RecordShareKind.LEDGER, 100L, List.of(2L))))
                .isInstanceOf(NotFoundException.class);
        verify(shares, never()).save(any());
    }

    @Test void rejectsRecipientOutsideGroup() throws Exception {
        when(entityManager.find(LedgerEntry.class, 100L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(source(sender));
        when(ledger.getEntryForSharing(1L, 100L)).thenReturn(snapshot());
        when(users.getRequiredUser(3L)).thenReturn(user(3L));
        assertThatThrownBy(() -> service.create(1L, new RecordShareDtos.Create(5L, RecordShareKind.LEDGER, 100L, List.of(3L))))
                .isInstanceOf(NotFoundException.class);
        verify(shares, never()).save(any());
    }

    @Test void acceptsEditedCopyOnceAndStripsSenderTravelLinks() throws Exception {
        RecordShare share = share(RecordShareKind.LEDGER);
        share.setShareMemo("당신 카드로 결제했어요.");
        share.setSnapshotJson(mapper.writeValueAsString(snapshot()));
        when(shares.findLockedById(9L)).thenReturn(Optional.of(share));
        when(ledger.getEntryForSharing(1L, 100L)).thenReturn(snapshot());
        when(ledger.create(eq(2L), any())).thenReturn(snapshot());
        var input = request("수정한 제목", 700L, 800L);

        var first = service.acceptLedger(2L, 9L, input);
        var second = service.acceptLedger(2L, 9L, input);

        var captor = ArgumentCaptor.forClass(LedgerEntryRequest.class);
        verify(ledger, times(1)).create(eq(2L), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("수정한 제목");
        assertThat(captor.getValue().memo()).isEqualTo("메모");
        assertThat(first.shareMemo()).isEqualTo("당신 카드로 결제했어요.");
        assertThat(captor.getValue().categoryGroupId()).isEqualTo(22L);
        assertThat(captor.getValue().paymentMethodId()).isEqualTo(24L);
        assertThat(captor.getValue().travelPlanId()).isNull();
        assertThat(captor.getValue().travelRecordId()).isNull();
        assertThat(first.importedLedgerEntryId()).isEqualTo(second.importedLedgerEntryId());
        assertThat(first.ledger().title()).isEqualTo("공유 시점 기록");
        assertThat(share.getStatus()).isEqualTo(RecordShareStatus.ACCEPTED);
    }

    @Test void invalidRecipientCatalogDoesNotAcceptRequest() {
        RecordShare share = share(RecordShareKind.LEDGER);
        when(shares.findLockedById(9L)).thenReturn(Optional.of(share));
        when(ledger.create(eq(2L), any())).thenThrow(new NotFoundException("대분류를 찾을 수 없습니다."));
        assertThatThrownBy(() -> service.acceptLedger(2L, 9L, request("title", null, null))).isInstanceOf(NotFoundException.class);
        assertThat(share.getStatus()).isEqualTo(RecordShareStatus.PENDING);
        assertThat(share.getImportedLedgerEntryId()).isNull();
    }

    @Test void deletedSourceCannotBeImported() {
        RecordShare share = share(RecordShareKind.LEDGER);
        when(shares.findLockedById(9L)).thenReturn(Optional.of(share));
        when(ledger.getEntryForSharing(1L, 100L)).thenThrow(new NotFoundException("거래를 찾을 수 없습니다."));
        assertThatThrownBy(() -> service.acceptLedger(2L, 9L, request("title", null, null))).isInstanceOf(NotFoundException.class);
        verify(ledger, never()).create(any(), any());
    }

    @Test void otherUserCannotApproveRequest() {
        when(shares.findLockedById(9L)).thenReturn(Optional.of(share(RecordShareKind.LEDGER)));
        assertThatThrownBy(() -> service.acceptLedger(1L, 9L, request("title", null, null))).isInstanceOf(NotFoundException.class);
        verify(ledger, never()).create(any(), any());
    }

    @Test void rejectedRequestCannotBeAccepted() {
        RecordShare share = share(RecordShareKind.LEDGER); share.setStatus(RecordShareStatus.REJECTED);
        when(shares.findLockedById(9L)).thenReturn(Optional.of(share));
        assertThatThrownBy(() -> service.acceptLedger(2L, 9L, request("title", null, null))).isInstanceOf(BadRequestException.class);
    }

    @Test void acceptedTravelCanBeRequestedAgainAfterMovingToAnotherGroup() {
        RecordShare old = share(RecordShareKind.TRAVEL); old.setStatus(RecordShareStatus.ACCEPTED);
        TravelShareGroup newGroup = new TravelShareGroup(); newGroup.setId(6L); newGroup.setOwner(sender); newGroup.setName("모임");
        when(groups.findById(6L)).thenReturn(Optional.of(newGroup));
        when(members.existsByGroupIdAndMemberId(6L, 2L)).thenReturn(true);
        TravelPlan plan = new TravelPlan(); plan.setOwner(sender); plan.setName("여행");
        when(entityManager.find(TravelPlan.class, 100L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(plan);
        when(shares.findLockedByKindAndSourceIdAndRecipientId(RecordShareKind.TRAVEL, 100L, 2L)).thenReturn(Optional.of(old));
        when(shares.save(old)).thenReturn(old);
        var response = service.create(1L, new RecordShareDtos.Create(6L, RecordShareKind.TRAVEL, 100L, List.of(2L)));
        assertThat(response.get(0).status()).isEqualTo(RecordShareStatus.PENDING);
        assertThat(response.get(0).groupId()).isEqualTo(6L);
    }

    @Test void acceptedTravelIsReferenceOnlyAndCanBeRevoked() {
        RecordShare share = share(RecordShareKind.TRAVEL);
        when(shares.findLockedById(9L)).thenReturn(Optional.of(share));
        service.acceptTravel(2L, 9L);
        assertThat(share.getStatus()).isEqualTo(RecordShareStatus.ACCEPTED);
        verify(travel).getRecordSharedPlanDetail(1L, 100L, 9L);
        verify(ledger, never()).create(any(), any());
        when(shares.findById(9L)).thenReturn(Optional.of(share));
        service.cancel(1L, 9L);
        assertThatThrownBy(() -> service.getTravel(2L, 9L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getTravelMedia(2L, 9L, 10L)).isInstanceOf(NotFoundException.class);
    }

    @Test void resharePreservesAnAlreadyAcceptedLedgerAcrossGroups() throws Exception {
        RecordShare accepted = share(RecordShareKind.LEDGER);
        accepted.setShareMemo("승인한 요청의 메모");
        accepted.setStatus(RecordShareStatus.ACCEPTED);
        accepted.setImportedLedgerEntryId(300L);
        TravelShareGroup nextGroup = new TravelShareGroup();
        nextGroup.setId(6L); nextGroup.setOwner(sender); nextGroup.setName("모임");
        when(groups.findById(6L)).thenReturn(Optional.of(nextGroup));
        when(members.existsByGroupIdAndMemberId(6L, 2L)).thenReturn(true);
        when(entityManager.find(LedgerEntry.class, 100L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(source(sender));
        when(ledger.getEntryForSharing(1L, 100L)).thenReturn(snapshot());
        when(shares.findLockedByKindAndSourceIdAndRecipientId(RecordShareKind.LEDGER, 100L, 2L)).thenReturn(Optional.of(accepted));

        var result = service.create(1L, new RecordShareDtos.Create(6L, RecordShareKind.LEDGER, 100L, List.of(2L), "변경 시도"));

        assertThat(result.get(0).status()).isEqualTo(RecordShareStatus.ACCEPTED);
        assertThat(result.get(0).groupId()).isEqualTo(5L);
        assertThat(result.get(0).importedLedgerEntryId()).isEqualTo(300L);
        assertThat(result.get(0).shareMemo()).isEqualTo("승인한 요청의 메모");
        verify(shares, never()).save(any());
        verify(ledger, never()).create(any(), any());
    }

    @Test void pendingTravelCannotBeOpenedIncludingItsPhotos() {
        when(shares.findById(9L)).thenReturn(Optional.of(share(RecordShareKind.TRAVEL)));
        assertThatThrownBy(() -> service.getTravel(2L, 9L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getTravelMedia(2L, 9L, 10L)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(travel);
    }

    @Test void removedMemberLosesAcceptedTravelAccess() {
        RecordShare share = share(RecordShareKind.TRAVEL); share.setStatus(RecordShareStatus.ACCEPTED);
        when(shares.findById(9L)).thenReturn(Optional.of(share));
        when(members.existsByGroupIdAndMemberId(5L, 2L)).thenReturn(false);
        assertThatThrownBy(() -> service.getTravelMedia(2L, 9L, 10L)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(travel);
    }

    @Test void senderCannotRemoveIndependentlyImportedLedger() {
        RecordShare share = share(RecordShareKind.LEDGER); share.setStatus(RecordShareStatus.ACCEPTED);
        when(shares.findLockedById(9L)).thenReturn(Optional.of(share));
        assertThatThrownBy(() -> service.cancel(1L, 9L)).isInstanceOf(BadRequestException.class);
        assertThat(share.getStatus()).isEqualTo(RecordShareStatus.ACCEPTED);
    }

    @Test void notificationsAndListsDoNotExposeGroupsUserHasLeft() {
        when(groups.findAccessibleGroups(2L)).thenReturn(List.of());
        assertThat(service.counts(2L)).isEqualTo(new RecordShareDtos.Counts(0, 0));
        assertThat(service.list(2L, RecordShareKind.LEDGER, false, null, 0, 10).items()).isEmpty();
        verifyNoInteractions(shares);
    }

    private AppUser user(Long id) { AppUser user = new AppUser(); user.setId(id); user.setLoginId("test-user-" + id); user.setDisplayName("User " + id); return user; }
    private LedgerEntry source(AppUser owner) { LedgerEntry entry = new LedgerEntry(); entry.setId(100L); entry.setOwner(owner); return entry; }
    private RecordShare share(RecordShareKind kind) {
        RecordShare share = new RecordShare(); share.setId(9L); share.setGroupId(5L); share.setGroupName("가족");
        share.setKind(kind); share.setSourceId(100L); share.setSender(sender); share.setRecipient(recipient); share.setTitle("기록"); share.setCreatedAt(LocalDateTime.now()); return share;
    }
    private LedgerEntryRequest request(String title, Long planId, Long recordId) {
        return new LedgerEntryRequest(LocalDate.of(2026, 10, 3), null, title, "메모", BigDecimal.TEN, null, null, null,
                EntryType.EXPENSE, 22L, 23L, 24L, planId, recordId);
    }
    private LedgerEntryResponse snapshot() throws Exception {
        return mapper.readValue("""
                {"id":100,"entryDate":"2026-10-03","title":"공유 시점 기록","memo":"메모","amount":10000,
                 "entryType":"EXPENSE","categoryGroupId":10,"categoryGroupName":"식비","categoryDetailId":11,
                 "categoryDetailName":"외식","paymentMethodId":12,"paymentMethodName":"카드","travelPlanId":700,"travelRecordId":800}
                """, LedgerEntryResponse.class);
    }
}
