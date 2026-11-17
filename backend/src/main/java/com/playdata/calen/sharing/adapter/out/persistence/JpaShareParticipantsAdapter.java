package com.playdata.calen.sharing.adapter.out.persistence;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.sharing.application.port.out.ShareParticipantsPort;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Actor;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Group;
import com.playdata.calen.travel.repository.TravelShareGroupMemberRepository;
import com.playdata.calen.travel.repository.TravelShareGroupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor @Transactional(propagation = Propagation.MANDATORY)
public class JpaShareParticipantsAdapter implements ShareParticipantsPort {
    private final AppUserService users;
    private final AppUserRepository accounts;
    private final TravelShareGroupRepository groups;
    private final TravelShareGroupMemberRepository members;
    private final EntityManager entities;

    @Override public void lockActiveActors(Long senderId, List<Long> recipientIds) {
        if (senderId == null || recipientIds == null || recipientIds.stream().anyMatch(id -> id == null)) throw unavailable();
        var ids = new TreeSet<Long>(recipientIds);
        ids.add(senderId);
        for (Long id : ids) {
            accounts.findActiveForPreferenceUpdate(id).orElseThrow(this::unavailable);
        }
    }

    @Override public Actor requireActiveActor(Long userId) {
        var user = users.getRequiredUser(userId);
        return new Actor(user.getId(), user.getDisplayName());
    }

    @Override public Group requireGroupAccess(Long groupId, Long userId) {
        users.getRequiredUser(userId);
        // Current locking reads prevent a request that began before group deletion
        // or ownership transfer from creating a new orphaned share afterwards.
        var group = groups.findForShareUpdate(groupId).orElseThrow(this::unavailable);
        try {
            entities.refresh(group, LockModeType.PESSIMISTIC_WRITE);
            if (!groupId.equals(group.getId()) || group.getOwner() == null || group.getOwner().getId() == null) throw unavailable();
            if (!group.getOwner().getId().equals(userId)) {
                var membership = members.findForShareUpdate(groupId, userId).orElseThrow(this::unavailable);
                entities.refresh(membership, LockModeType.PESSIMISTIC_WRITE);
                if (membership.getGroup() == null || !groupId.equals(membership.getGroup().getId())
                        || membership.getMember() == null || !userId.equals(membership.getMember().getId())) throw unavailable();
            }
        } catch (EntityNotFoundException missing) {
            throw unavailable();
        }
        return new Group(group.getId(), group.getName());
    }

    private NotFoundException unavailable() { return new NotFoundException("접근 가능한 기록 공유 요청을 찾을 수 없습니다."); }
}
