package com.playdata.calen.sharing.application.port.out;

import com.playdata.calen.sharing.domain.model.ShareCreationData.Actor;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Group;

public interface ShareParticipantsPort {
    Actor requireActiveActor(Long userId);
    Group requireGroupAccess(Long groupId, Long userId);
}
