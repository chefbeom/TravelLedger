package com.playdata.calen.sharing.adapter.out.persistence;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.sharing.application.port.out.ShareParticipantsPort;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Actor;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Group;
import com.playdata.calen.travel.repository.TravelShareGroupMemberRepository;
import com.playdata.calen.travel.repository.TravelShareGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor @Transactional(propagation = Propagation.MANDATORY)
public class JpaShareParticipantsAdapter implements ShareParticipantsPort {
    private final AppUserService users;
    private final TravelShareGroupRepository groups;
    private final TravelShareGroupMemberRepository members;

    @Override public Actor requireActiveActor(Long userId) {
        var user = users.getRequiredUser(userId);
        return new Actor(user.getId(), user.getDisplayName());
    }

    @Override public Group requireGroupAccess(Long groupId, Long userId) {
        users.getRequiredUser(userId);
        var group = groups.findById(groupId).orElseThrow(this::unavailable);
        if (!group.getOwner().getId().equals(userId) && !members.existsByGroupIdAndMemberId(groupId, userId)) throw unavailable();
        return new Group(group.getId(), group.getName());
    }

    private NotFoundException unavailable() { return new NotFoundException("접근 가능한 기록 공유 요청을 찾을 수 없습니다."); }
}
