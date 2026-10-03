package com.playdata.calen.sharing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.service.LedgerEntryService;
import com.playdata.calen.sharing.domain.*;
import com.playdata.calen.sharing.dto.RecordShareDtos;
import com.playdata.calen.sharing.repository.RecordShareRepository;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.dto.TravelSharedExhibitDetailResponse;
import com.playdata.calen.travel.repository.TravelShareGroupMemberRepository;
import com.playdata.calen.travel.repository.TravelShareGroupRepository;
import com.playdata.calen.travel.service.TravelService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class RecordSharingService {
    private final RecordShareRepository shares;
    private final TravelShareGroupRepository groups;
    private final TravelShareGroupMemberRepository members;
    private final AppUserService users;
    private final LedgerEntryService ledger;
    private final TravelService travel;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;

    public List<RecordShareDtos.Group> getGroups(Long userId) {
        users.getRequiredUser(userId);
        List<TravelShareGroup> visible = groups.findAccessibleGroups(userId);
        if (visible.isEmpty()) return List.of();
        Map<Long, List<TravelShareGroupMember>> byGroup = new HashMap<>();
        for (var member : members.findAllByGroupIdInOrderByIdAsc(visible.stream().map(TravelShareGroup::getId).toList())) {
            byGroup.computeIfAbsent(member.getGroup().getId(), ignored -> new ArrayList<>()).add(member);
        }
        return visible.stream().map(group -> {
            Map<Long, RecordShareDtos.Member> participants = new LinkedHashMap<>();
            addMember(participants, group.getOwner(), userId);
            byGroup.getOrDefault(group.getId(), List.of()).forEach(member -> addMember(participants, member.getMember(), userId));
            return new RecordShareDtos.Group(group.getId(), group.getName(), group.getOwner().getId(), List.copyOf(participants.values()));
        }).toList();
    }

    private void addMember(Map<Long, RecordShareDtos.Member> target, AppUser user, Long viewerId) {
        if (user.isActive()) target.put(user.getId(), new RecordShareDtos.Member(user.getId(), user.getLoginId(), user.getDisplayName(), user.getId().equals(viewerId)));
    }

    @Transactional
    public List<RecordShareDtos.Response> create(Long userId, RecordShareDtos.Create request) {
        String shareMemo = normalizeShareMemo(request.shareMemo());
        AppUser sender = users.getRequiredUser(userId);
        TravelShareGroup group = requireGroup(request.groupId(), userId);
        // Serialize requests for the same source, including requests sent through different groups.
        Object source = request.kind() == RecordShareKind.LEDGER
                ? entityManager.find(LedgerEntry.class, request.sourceId(), LockModeType.PESSIMISTIC_WRITE)
                : entityManager.find(TravelPlan.class, request.sourceId(), LockModeType.PESSIMISTIC_WRITE);
        String title;
        String snapshot = null;
        if (source instanceof LedgerEntry entry && entry.getOwner().getId().equals(userId) && entry.getDeletedAt() == null) {
            var response = ledger.getEntryForSharing(userId, request.sourceId());
            title = response.title();
            try { snapshot = mapper.writeValueAsString(response); }
            catch (JsonProcessingException ex) { throw new IllegalStateException("Unable to prepare record share."); }
        } else if (source instanceof TravelPlan plan && plan.getOwner().getId().equals(userId)) {
            title = plan.getName();
        } else {
            throw new NotFoundException("공유할 본인 기록을 찾을 수 없습니다.");
        }
        List<RecordShareDtos.Response> result = new ArrayList<>();
        for (Long recipientId : new LinkedHashSet<>(request.recipientIds())) {
            if (userId.equals(recipientId)) throw new BadRequestException("본인에게 기록을 공유할 수 없습니다.");
            requireGroup(group.getId(), recipientId);
            AppUser recipient = users.getRequiredUser(recipientId);
            // An approval locks this same row. Read its latest committed state
            // under the lock so a concurrent re-share cannot undo acceptance.
            RecordShare share = shares.findLockedByKindAndSourceIdAndRecipientId(request.kind(), request.sourceId(), recipientId).orElseGet(RecordShare::new);
            boolean importedLedger = request.kind() == RecordShareKind.LEDGER && share.getStatus() == RecordShareStatus.ACCEPTED;
            boolean existingGroupRequest = share.getId() != null && Objects.equals(share.getGroupId(), group.getId())
                    && (share.getStatus() == RecordShareStatus.PENDING || share.getStatus() == RecordShareStatus.ACCEPTED);
            if (importedLedger || existingGroupRequest) {
                result.add(toResponse(share));
                continue;
            }
            share.setGroupId(group.getId());
            share.setGroupName(group.getName());
            share.setKind(request.kind());
            share.setSourceId(request.sourceId());
            share.setSender(sender);
            share.setRecipient(recipient);
            share.setTitle(title);
            share.setSnapshotJson(snapshot);
            share.setShareMemo(shareMemo);
            share.setStatus(RecordShareStatus.PENDING);
            share.setCreatedAt(LocalDateTime.now());
            share.setRespondedAt(null);
            result.add(toResponse(shares.save(share)));
        }
        return result;
    }

    public RecordShareDtos.Page list(Long userId, RecordShareKind kind, boolean sent, RecordShareStatus status, int page, int size) {
        List<Long> ids = accessibleIds(userId);
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(size, 50));
        if (ids.isEmpty()) return new RecordShareDtos.Page(List.of(), safePage, safeSize, 0, 0);
        var result = shares.findRequests(userId, kind, sent, status, ids,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new RecordShareDtos.Page(result.map(this::toResponse).getContent(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public RecordShareDtos.Counts counts(Long userId) {
        List<Long> ids = accessibleIds(userId);
        if (ids.isEmpty()) return new RecordShareDtos.Counts(0, 0);
        return new RecordShareDtos.Counts(shares.countPending(userId, RecordShareKind.LEDGER, ids),
                shares.countPending(userId, RecordShareKind.TRAVEL, ids));
    }

    private List<Long> accessibleIds(Long userId) {
        users.getRequiredUser(userId);
        return groups.findAccessibleGroups(userId).stream().map(TravelShareGroup::getId).toList();
    }

    @Transactional
    public RecordShareDtos.Response acceptLedger(Long userId, Long id, LedgerEntryRequest request) {
        RecordShare share = requireRecipient(userId, id, true);
        if (share.getKind() != RecordShareKind.LEDGER) throw new BadRequestException("가계부 공유 요청이 아닙니다.");
        if (share.getStatus() == RecordShareStatus.ACCEPTED) return toResponse(share);
        requirePending(share);
        ledger.getEntryForSharing(share.getSender().getId(), share.getSourceId());
        // Source taxonomy/travel IDs belong to the sender. Only receiver-owned taxonomy is accepted.
        var ownRequest = new LedgerEntryRequest(request.entryDate(), request.entryTime(), request.title(), request.memo(),
                request.amount(), request.foreignCurrencyCode(), request.foreignAmount(), request.exchangeRateToKrw(),
                request.entryType(), request.categoryGroupId(), request.categoryDetailId(), request.paymentMethodId(), null, null);
        LedgerEntryResponse created = ledger.create(userId, ownRequest);
        share.setImportedLedgerEntryId(created.id());
        accept(share);
        return toResponse(share);
    }

    @Transactional
    public RecordShareDtos.Response acceptTravel(Long userId, Long id) {
        RecordShare share = requireRecipient(userId, id, true);
        if (share.getKind() != RecordShareKind.TRAVEL) throw new BadRequestException("여행 공유 요청이 아닙니다.");
        if (share.getStatus() == RecordShareStatus.ACCEPTED) return toResponse(share);
        requirePending(share);
        travel.getRecordSharedPlanDetail(share.getSender().getId(), share.getSourceId(), share.getId());
        accept(share);
        return toResponse(share);
    }

    @Transactional
    public RecordShareDtos.Response reject(Long userId, Long id) {
        RecordShare share = requireRecipient(userId, id, true);
        requirePending(share);
        share.setStatus(RecordShareStatus.REJECTED);
        share.setRespondedAt(LocalDateTime.now());
        return toResponse(share);
    }

    @Transactional
    public RecordShareDtos.Response cancel(Long userId, Long id) {
        users.getRequiredUser(userId);
        RecordShare share = shares.findLockedById(id).orElseThrow(this::notFound);
        if (!share.getSender().getId().equals(userId)) throw notFound();
        if (share.getStatus() == RecordShareStatus.ACCEPTED && share.getKind() == RecordShareKind.LEDGER)
            throw new BadRequestException("이미 가져온 가계부 거래는 받는 사람의 독립 기록입니다.");
        share.setStatus(RecordShareStatus.CANCELED);
        share.setRespondedAt(LocalDateTime.now());
        return toResponse(share);
    }

    public TravelSharedExhibitDetailResponse getTravel(Long userId, Long id) {
        RecordShare share = requireAcceptedTravel(userId, id);
        var detail = travel.getRecordSharedPlanDetail(share.getSender().getId(), share.getSourceId(), share.getId());
        return new TravelSharedExhibitDetailResponse(detail.id(), detail.sharedByLoginId(), detail.sharedByDisplayName(), share.getCreatedAt(), detail.travelPlan());
    }

    public TravelService.MediaDownload getTravelMedia(Long userId, Long id, Long mediaId) {
        RecordShare share = requireAcceptedTravel(userId, id);
        return travel.getRecordSharedMediaDownload(share.getSender().getId(), share.getSourceId(), mediaId);
    }

    private RecordShare requireAcceptedTravel(Long userId, Long id) {
        RecordShare share = requireRecipient(userId, id, false);
        if (share.getKind() != RecordShareKind.TRAVEL || share.getStatus() != RecordShareStatus.ACCEPTED) throw notFound();
        return share;
    }

    private RecordShare requireRecipient(Long userId, Long id, boolean lock) {
        users.getRequiredUser(userId);
        RecordShare share = (lock ? shares.findLockedById(id) : shares.findById(id)).orElseThrow(this::notFound);
        if (!share.getRecipient().getId().equals(userId)) throw notFound();
        requireGroup(share.getGroupId(), userId);
        requireGroup(share.getGroupId(), share.getSender().getId());
        return share;
    }

    private TravelShareGroup requireGroup(Long groupId, Long userId) {
        users.getRequiredUser(userId);
        TravelShareGroup group = groups.findById(groupId).orElseThrow(this::notFound);
        if (!group.getOwner().getId().equals(userId) && !members.existsByGroupIdAndMemberId(groupId, userId)) throw notFound();
        return group;
    }

    private void requirePending(RecordShare share) {
        if (share.getStatus() != RecordShareStatus.PENDING) throw new BadRequestException("이미 처리된 공유 요청입니다.");
    }
    private void accept(RecordShare share) {
        share.setStatus(RecordShareStatus.ACCEPTED);
        share.setRespondedAt(LocalDateTime.now());
    }
    private NotFoundException notFound() { return new NotFoundException("접근 가능한 기록 공유 요청을 찾을 수 없습니다."); }
    private String normalizeShareMemo(String memo) {
        if (memo == null) return null;
        if (memo.length() > 500) throw new BadRequestException("공유 메모는 500자까지 입력할 수 있습니다.");
        return memo.isBlank() ? null : memo.strip();
    }
    private RecordShareDtos.Response toResponse(RecordShare share) {
        LedgerEntryResponse snapshot = null;
        if (share.getSnapshotJson() != null) {
            try { snapshot = mapper.readValue(share.getSnapshotJson(), LedgerEntryResponse.class); }
            catch (JsonProcessingException ex) { throw new IllegalStateException("Unable to read shared record."); }
        }
        return new RecordShareDtos.Response(share.getId(), share.getGroupId(), share.getGroupName(), share.getKind(), share.getSourceId(),
                share.getSender().getId(), share.getSender().getDisplayName(), share.getRecipient().getId(), share.getRecipient().getDisplayName(),
                share.getStatus(), share.getTitle(), snapshot, share.getImportedLedgerEntryId(), share.getCreatedAt(), share.getRespondedAt(),
                share.getShareMemo());
    }
}
