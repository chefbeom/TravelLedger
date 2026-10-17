package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelSharingData.*;
import java.util.List;

public interface ManageTravelSharingUseCase {
    Share shareCompletedPlan(Long owner, Long plan, String recipientLogin);
    List<Share> shares(Long owner, Long plan);
    void cancelShare(Long owner, Long plan, Long share);
    List<Recipient> searchRecipients(Long owner, String query);
    List<Group> groups(Long owner);
    Group saveGroup(Long owner, String name, List<String> recipientLogins);
    void deleteGroup(Long owner, Long group);
    PublicShare publicShare(Long owner, Long plan, Boolean enabled);
}
