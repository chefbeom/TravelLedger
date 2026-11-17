package com.playdata.calen.sharing.application.port.out;

import com.playdata.calen.sharing.domain.model.ShareCreationData.Actor;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Group;
import java.util.List;

public interface ShareParticipantsPort {
    /** Fence all participants until the surrounding share-creation transaction ends. */
    void lockActiveActors(Long senderId, List<Long> recipientIds);
    Actor requireActiveActor(Long userId);
    Group requireGroupAccess(Long groupId, Long userId);
}
