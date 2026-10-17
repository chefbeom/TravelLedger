package com.playdata.calen.travel.adapter.out.persistence;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.TravelSharingStore;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelSharingData.*;
import com.playdata.calen.travel.repository.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaTravelSharingAdapter implements TravelSharingStore {
    private final AppUserService users;
    private final AppUserRepository accounts;
    private final TravelPlanRepository plans;
    private final TravelPlanShareRepository shares;
    private final TravelShareGroupRepository groups;
    private final TravelShareGroupMemberRepository members;

    @Override
    public Recipient activeOwner(Long owner) { return recipient(users.getRequiredUser(owner)); }

    @Override
    public Optional<Recipient> activeRecipient(String login) { return users.findActiveUserByLoginId(login).map(this::recipient); }

    @Override
    public Plan ownedPlan(Long owner, Long id) {
        var plan = plans.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Travel plan not found."));
        return new Plan(plan.getId(), plan.getStatus());
    }

    @Override
    public Optional<Long> findShare(Long plan, Long recipient) {
        return shares.findByPlanIdAndRecipientId(plan, recipient).map(TravelPlanShare::getId);
    }

    @Override
    public Long createShare(Long owner, Long plan, Long recipient) {
        var share = new TravelPlanShare();
        share.setPlan(plans.getReferenceById(plan));
        share.setSharedBy(accounts.getReferenceById(owner));
        share.setRecipient(accounts.getReferenceById(recipient));
        share.setCreatedAt(LocalDateTime.now());
        return shares.save(share).getId();
    }

    @Override
    public Share share(Long id) { return snapshot(shares.getReferenceById(id)); }

    @Override
    public List<Share> shares(Long owner, Long plan) {
        return shares.findAllByPlanIdAndSharedByIdOrderByCreatedAtDescIdDesc(plan, owner).stream().map(this::snapshot).toList();
    }

    @Override
    public void cancelShare(Long owner, Long plan, Long id) {
        var share = shares.findByIdAndPlanIdAndSharedById(id, plan, owner)
                .orElseThrow(() -> new NotFoundException("공유 내역을 찾을 수 없습니다."));
        shares.delete(share);
    }

    @Override
    public List<Recipient> searchRecipients(Long owner, String query, int limit) {
        return users.searchActiveUsersForSharing(owner, query, limit).stream().map(this::recipient).toList();
    }

    @Override
    public List<Group> groups(Long owner) {
        var membersByGroup = members.findAllByGroupOwnerIdOrderByGroupIdAscIdAsc(owner).stream()
                .collect(Collectors.groupingBy(member -> member.getGroup().getId()));
        return groups.findAllByOwnerIdOrderByUpdatedAtDescIdDesc(owner).stream()
                .map(group -> snapshot(group, membersByGroup.getOrDefault(group.getId(), List.of())))
                .toList();
    }

    @Override
    public Group replaceGroup(Long owner, String name, List<Recipient> recipients) {
        LocalDateTime now = LocalDateTime.now();
        var group = groups.findByOwnerIdAndNameIgnoreCase(owner, name).orElseGet(() -> {
            var created = new TravelShareGroup();
            created.setOwner(accounts.getReferenceById(owner));
            created.setCreatedAt(now);
            return created;
        });
        group.setName(name);
        group.setUpdatedAt(now);
        var saved = groups.save(group);
        if (saved.getId() != null) {
            members.deleteAllByGroupId(saved.getId());
            members.flush();
        }
        var replacements = recipients.stream().map(recipient -> {
            var member = new TravelShareGroupMember();
            member.setGroup(saved);
            member.setMember(accounts.getReferenceById(recipient.id()));
            member.setCreatedAt(now);
            return member;
        }).toList();
        return snapshot(saved, members.saveAll(replacements));
    }

    @Override
    public void deleteGroup(Long owner, Long id) {
        var group = groups.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("공유 그룹을 찾을 수 없습니다."));
        members.deleteAllByGroupId(group.getId());
        groups.delete(group);
    }

    @Override
    public void setPublicShare(Long ownedPlan, boolean enabled) {
        var plan = plans.getReferenceById(ownedPlan);
        plan.setPublicShared(enabled);
        plan.setPublicSharedAt(enabled ? LocalDateTime.now() : null);
    }

    @Override
    public PublicShare publicShare(Long ownedPlan) {
        var plan = plans.getReferenceById(ownedPlan);
        return new PublicShare(plan.getId(), Boolean.TRUE.equals(plan.getPublicShared()), plan.getPublicSharedAt());
    }

    private Recipient recipient(AppUser user) { return new Recipient(user.getId(), user.getLoginId(), user.getDisplayName()); }

    private Share snapshot(TravelPlanShare share) {
        return new Share(share.getId(), share.getPlan().getId(), share.getPlan().getName(), share.getRecipient().getLoginId(),
                share.getRecipient().getDisplayName(), share.getCreatedAt());
    }

    private Group snapshot(TravelShareGroup group, List<TravelShareGroupMember> members) {
        return new Group(group.getId(), group.getName(), members.stream().map(TravelShareGroupMember::getMember).map(this::recipient).toList(),
                group.getCreatedAt(), group.getUpdatedAt());
    }
}
