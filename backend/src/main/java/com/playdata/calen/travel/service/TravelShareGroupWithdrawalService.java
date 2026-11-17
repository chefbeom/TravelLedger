package com.playdata.calen.travel.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.sharing.repository.RecordShareRepository;
import com.playdata.calen.travel.domain.TravelShareGroup;
import com.playdata.calen.travel.domain.TravelShareGroupMember;
import com.playdata.calen.travel.repository.TravelShareGroupMemberRepository;
import com.playdata.calen.travel.repository.TravelShareGroupRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Preserves other members' sharing groups during withdrawal; not itself a withdrawal endpoint. */
@Service
@RequiredArgsConstructor
public class TravelShareGroupWithdrawalService {
    private final TravelShareGroupRepository groups;
    private final TravelShareGroupMemberRepository members;
    private final RecordShareRepository shares;
    private final AppUserRepository users;

    @Transactional(readOnly = true)
    public List<TransferChoice> requiredTransfers(Long userId) {
        List<TravelShareGroup> owned = groups.findAllByOwnerIdOrderByUpdatedAtDescIdDesc(userId);
        List<TravelShareGroupMember> membership = owned.isEmpty() ? List.of()
                : members.findAllByGroupOwnerIdOrderByGroupIdAscIdAsc(userId);
        return buildChoices(userId, owned, membership);
    }

    /** Run only after the caller locks the withdrawing user, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transferForWithdrawal(Long userId, Map<Long, Long> selectedOwners) {
        List<TravelShareGroup> owned = groups.lockOwnedForWithdrawal(userId);
        List<TravelShareGroupMember> membership = owned.isEmpty() ? List.of()
                : members.lockForWithdrawal(owned.stream().map(TravelShareGroup::getId).toList());
        List<TransferChoice> required = buildChoices(userId, owned, membership);
        Map<Long, Long> selections = selectedOwners == null ? Map.of() : new HashMap<>(selectedOwners);
        if (selections.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)
                || !selections.keySet().equals(required.stream().map(TransferChoice::groupId).collect(Collectors.toSet()))) {
            throw new BadRequestException("공유 그룹마다 소유권을 이전할 멤버를 선택해 주세요.");
        }

        Map<Long, TravelShareGroup> byId = owned.stream().collect(Collectors.toMap(TravelShareGroup::getId, Function.identity()));
        Map<Long, TravelShareGroupMember> selectedMembers = new HashMap<>();
        Set<Long> targetIds = new TreeSet<>();
        for (TransferChoice choice : required) {
            Long target = selections.get(choice.groupId());
            TravelShareGroupMember member = membership.stream()
                    .filter(value -> Objects.equals(value.getGroup().getId(), choice.groupId()))
                    .filter(value -> Objects.equals(value.getMember().getId(), target))
                    .filter(value -> !Objects.equals(value.getMember().getId(), userId) && value.getMember().isActive())
                    .findFirst().orElseThrow(() -> new BadRequestException("공유 그룹의 활성 멤버를 선택해 주세요."));
            selectedMembers.put(choice.groupId(), member);
            targetIds.add(target);
        }

        Map<Long, AppUser> lockedTargets = new HashMap<>();
        for (Long target : targetIds) {
            lockedTargets.put(target, users.findActiveForPreferenceUpdate(target)
                    .orElseThrow(() -> new BadRequestException("이전할 공유 그룹의 멤버가 더 이상 활성 상태가 아닙니다.")));
        }
        Set<String> transferNames = new HashSet<>();
        for (TransferChoice choice : required) {
            Long target = selections.get(choice.groupId());
            TravelShareGroup group = byId.get(choice.groupId());
            String transferKey = target + "\0" + group.getName().toLowerCase(Locale.ROOT);
            if (!transferNames.add(transferKey) || !groups.lockNameConflicts(target, group.getName()).isEmpty()) {
                throw new BadRequestException("이전할 멤버에게 같은 이름의 공유 그룹이 있습니다. 다른 멤버를 선택하거나 그룹 이름을 먼저 변경해 주세요.");
            }
        }
        // Validate every choice before modifying anything. The selected owner's member row is
        // removed because group owners already have access and cannot also be recipients.
        for (TransferChoice choice : required) {
            TravelShareGroup group = byId.get(choice.groupId());
            group.setOwner(lockedTargets.get(selections.get(choice.groupId())));
            group.setUpdatedAt(LocalDateTime.now());
            members.delete(selectedMembers.get(choice.groupId()));
        }
    }

    private List<TransferChoice> buildChoices(Long userId, List<TravelShareGroup> owned,
            List<TravelShareGroupMember> membership) {
        if (owned.isEmpty()) return List.of();
        Map<Long, List<TravelShareGroupMember>> byGroup = membership.stream()
                .filter(member -> !Objects.equals(member.getMember().getId(), userId))
                .collect(Collectors.groupingBy(member -> member.getGroup().getId()));
        Set<Long> foreignShares = new HashSet<>(shares.findGroupsContainingOtherUsersShares(
                owned.stream().map(TravelShareGroup::getId).toList(), userId));
        return owned.stream().filter(group -> !byGroup.getOrDefault(group.getId(), List.of()).isEmpty()
                        || foreignShares.contains(group.getId()))
                .map(group -> {
                    List<TravelShareGroupMember> groupMembers = byGroup.getOrDefault(group.getId(), List.of());
                    Set<Long> activeIds = groupMembers.stream().map(TravelShareGroupMember::getMember)
                            .filter(AppUser::isActive).map(AppUser::getId).collect(Collectors.toSet());
                    Set<Long> collisions = activeIds.isEmpty() ? Set.of()
                            : new HashSet<>(groups.findOwnersWithName(activeIds, group.getName()));
                    List<Candidate> candidates = groupMembers.stream().map(TravelShareGroupMember::getMember)
                            .map(user -> new Candidate(user.getId(), user.getDisplayName(), user.getLoginId(),
                                    user.isActive() && !collisions.contains(user.getId()),
                                    !user.isActive() ? "비활성 계정" : collisions.contains(user.getId()) ? "같은 이름의 그룹이 있음" : null))
                            .toList();
                    return new TransferChoice(group.getId(), group.getName(), foreignShares.contains(group.getId()), candidates);
                }).toList();
    }

    public record Candidate(Long userId, String displayName, String loginId, boolean eligible, String unavailableReason) {}
    public record TransferChoice(Long groupId, String groupName, boolean containsOthersShares, List<Candidate> members) {}
}
