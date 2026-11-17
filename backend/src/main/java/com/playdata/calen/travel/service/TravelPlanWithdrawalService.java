package com.playdata.calen.travel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequest;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.sharing.domain.RecordShare;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.RecordShareStatus;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanShare;
import com.playdata.calen.travel.domain.TravelShareGroup;
import com.playdata.calen.travel.domain.TravelShareGroupMember;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Finds both kinds of live plan sharing before any withdrawal removes sharing edges. */
@Service
@RequiredArgsConstructor
public class TravelPlanWithdrawalService {
    private final EntityManager entities;
    private final ObjectMapper json;

    /** Counts only the caller's media; no details of otherwise inaccessible plans are exposed. */
    @Transactional(readOnly = true)
    public ForeignMediaSummary foreignMediaSummary(Long uploaderId) {
        if (uploaderId == null || uploaderId <= 0) throw new IllegalArgumentException("Uploader is required.");
        Object[] counts = entities.createQuery("""
                select count(distinct asset.plan.id), count(asset.id) from TravelMediaAsset asset
                where asset.uploadedBy.id = :uploader and asset.plan.owner.id <> :uploader
                """, Object[].class).setParameter("uploader", uploaderId).getSingleResult();
        return new ForeignMediaSummary((Long) counts[0], (Long) counts[1]);
    }

    @Transactional(readOnly = true)
    public List<TransferChoice> requiredTransfers(Long ownerId) {
        if (ownerId == null || ownerId <= 0) throw new IllegalArgumentException("Owner is required.");
        List<TravelPlanShare> direct = entities.createQuery("""
                select share from TravelPlanShare share
                join fetch share.plan plan left join fetch share.recipient
                left join fetch share.sharedBy
                where plan.owner.id = :owner order by plan.id, share.id
                """, TravelPlanShare.class).setParameter("owner", ownerId).getResultList();
        List<Object[]> grouped = entities.createQuery("""
                select plan, share from TravelPlan plan, RecordShare share
                where plan.owner.id = :owner and share.kind = :kind
                  and share.sourceId = plan.id and share.status in :statuses
                order by plan.id, share.id
                """, Object[].class).setParameter("owner", ownerId).setParameter("kind", RecordShareKind.TRAVEL)
                .setParameter("statuses", List.of(RecordShareStatus.PENDING, RecordShareStatus.ACCEPTED))
                .getResultList();

        Map<Long, PlanShares> plans = new LinkedHashMap<>();
        for (TravelPlanShare share : direct) {
            PlanShares found = plans.computeIfAbsent(share.getPlan().getId(), ignored -> new PlanShares(share.getPlan()));
            found.direct = true;
            found.inconsistent |= share.getSharedBy() == null || !ownerId.equals(share.getSharedBy().getId());
            addCandidate(found, share.getRecipient(), ownerId);
        }
        for (Object[] row : grouped) {
            TravelPlan plan = (TravelPlan) row[0];
            RecordShare share = (RecordShare) row[1];
            PlanShares found = plans.computeIfAbsent(plan.getId(), ignored -> new PlanShares(plan));
            found.inconsistent |= share.getSender() == null || !ownerId.equals(share.getSender().getId());
            found.groups.add(share.getGroupId());
            addCandidate(found, share.getRecipient(), ownerId);
        }
        List<TravelPlan> published = entities.createQuery("""
                select plan from TravelPlan plan where plan.owner.id = :owner and
                  (plan.publicShared = true or exists (select record.id from TravelExpenseRecord record
                    where record.plan = plan and record.sharedWithCommunity = true))
                order by plan.id
                """, TravelPlan.class).setParameter("owner", ownerId).getResultList();
        for (TravelPlan plan : published) {
            plans.computeIfAbsent(plan.getId(), ignored -> new PlanShares(plan)).publiclyShared = true;
        }
        Set<Long> linkedPlanIds = publicMapPlanIds(ownerId);
        if (!linkedPlanIds.isEmpty()) {
            List<TravelPlan> linkedPlans = entities.createQuery("""
                    select plan from TravelPlan plan where plan.owner.id = :owner and plan.id in :ids order by plan.id
                    """, TravelPlan.class).setParameter("owner", ownerId).setParameter("ids", linkedPlanIds).getResultList();
            for (TravelPlan plan : linkedPlans) {
                plans.computeIfAbsent(plan.getId(), ignored -> new PlanShares(plan)).publiclyShared = true;
            }
        }
        Set<Long> groupIds = new LinkedHashSet<>();
        plans.values().forEach(plan -> groupIds.addAll(plan.groups));
        Map<Long, Set<Long>> groupParticipants = groupParticipants(groupIds);
        Set<Long> candidateIds = new LinkedHashSet<>();
        plans.values().forEach(plan -> candidateIds.addAll(plan.candidates.keySet()));
        Set<Long> withdrawing = candidateIds.isEmpty() ? Set.of() : new LinkedHashSet<>(entities.createQuery("""
                select request.userId from AccountWithdrawalRequest request
                where request.userId in :ids and request.state = :state
                """, Long.class).setParameter("ids", candidateIds)
                .setParameter("state", AccountWithdrawalRequest.State.IN_PROGRESS).getResultList());
        List<TransferChoice> result = new ArrayList<>();
        for (PlanShares plan : plans.values()) {
            List<Candidate> candidates = plan.candidates.values().stream().map(user -> {
                String reason = !user.isActive() ? "비활성 계정"
                        : withdrawing.contains(user.getId()) ? "탈퇴 처리 중인 계정"
                        : plan.inconsistent ? "기존 여행 공유의 소유 정보 확인 필요"
                        : plan.groups.stream().anyMatch(group -> !groupParticipants.getOrDefault(group, Set.of()).contains(user.getId()))
                                ? "기존 공유를 유지하려면 해당 여행의 모든 공유 그룹에 참여해야 함" : null;
                return new Candidate(user.getId(), user.getDisplayName(), user.getLoginId(), reason == null, reason);
            }).toList();
            result.add(new TransferChoice(plan.plan.getId(), plan.plan.getName(), plan.direct,
                    plan.groups.size(), plan.publiclyShared, candidates));
        }
        return result.stream().sorted(java.util.Comparator.comparing(TransferChoice::planId)).toList();
    }

    /** Cleanup must never discard a shared plan that was not transferred. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireNoOwnedSharedPlansRemain(Long ownerId) {
        if (!requiredTransfers(ownerId).isEmpty()) {
            throw new BadRequestException("이전되지 않은 공유 여행 계획과 사진이 있어 탈퇴할 수 없습니다.");
        }
    }

    private Map<Long, Set<Long>> groupParticipants(Collection<Long> groupIds) {
        if (groupIds.isEmpty()) return Map.of();
        Map<Long, Set<Long>> result = new LinkedHashMap<>();
        for (TravelShareGroup group : entities.createQuery("select g from TravelShareGroup g join fetch g.owner where g.id in :ids", TravelShareGroup.class)
                .setParameter("ids", groupIds).getResultList()) {
            result.computeIfAbsent(group.getId(), ignored -> new LinkedHashSet<>()).add(group.getOwner().getId());
        }
        for (TravelShareGroupMember member : entities.createQuery("""
                select member from TravelShareGroupMember member join fetch member.member
                where member.group.id in :ids
                """, TravelShareGroupMember.class).setParameter("ids", groupIds).getResultList()) {
            result.computeIfAbsent(member.getGroup().getId(), ignored -> new LinkedHashSet<>()).add(member.getMember().getId());
        }
        return result;
    }

    private Set<Long> publicMapPlanIds(Long ownerId) {
        List<String> scopes = entities.createQuery("""
                select link.planIdsJson from TravelMapShareLink link
                where link.owner.id = :owner and link.active = true
                """, String.class).setParameter("owner", ownerId).getResultList();
        Set<Long> result = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null || scope.isBlank()) continue;
            JsonNode plans;
            try { plans = json.readTree(scope); }
            catch (JsonProcessingException failure) { throw invalidPublicScope(); }
            if (plans == null || !plans.isArray()) throw invalidPublicScope();
            for (JsonNode id : plans) {
                if (id.isNull()) continue;
                if (!id.isIntegralNumber() || !id.canConvertToLong()) throw invalidPublicScope();
                if (id.longValue() > 0) result.add(id.longValue());
            }
        }
        return result;
    }

    private BadRequestException invalidPublicScope() {
        return new BadRequestException("공개 여행 지도의 공유 대상을 확인할 수 없습니다. 공유 설정을 먼저 확인해 주세요.");
    }

    private void addCandidate(PlanShares plan, AppUser user, Long ownerId) {
        if (user != null && user.getId() != null && !ownerId.equals(user.getId())) plan.candidates.putIfAbsent(user.getId(), user);
    }

    private static final class PlanShares {
        final TravelPlan plan;
        final Map<Long, AppUser> candidates = new LinkedHashMap<>();
        final Set<Long> groups = new LinkedHashSet<>();
        boolean direct;
        boolean inconsistent;
        boolean publiclyShared;
        PlanShares(TravelPlan plan) { this.plan = plan; }
    }

    public record Candidate(Long userId, String displayName, String loginId, boolean eligible, String unavailableReason) {}
    public record TransferChoice(Long planId, String planName, boolean directlyShared, int sharedGroupCount,
                                 boolean publiclyShared, List<Candidate> members) {}
    public record ForeignMediaSummary(long planCount, long mediaCount) {}
}
