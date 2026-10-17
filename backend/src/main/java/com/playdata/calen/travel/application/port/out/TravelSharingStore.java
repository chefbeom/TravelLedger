package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelSharingData.*;
import java.util.List;
import java.util.Optional;

/** All operations join the caller's transaction; snapshots never expose managed entities. */
public interface TravelSharingStore {
    Recipient activeOwner(Long owner);
    Optional<Recipient> activeRecipient(String login);
    Plan ownedPlan(Long owner, Long plan);
    Optional<Long> findShare(Long plan, Long recipient);
    Long createShare(Long owner, Long plan, Long recipient);
    Share share(Long share);
    List<Share> shares(Long owner, Long plan);
    void cancelShare(Long owner, Long plan, Long share);
    List<Recipient> searchRecipients(Long owner, String query, int limit);
    List<Group> groups(Long owner);
    Group replaceGroup(Long owner, String name, List<Recipient> recipients);
    void deleteGroup(Long owner, Long group);
    void setPublicShare(Long ownedPlan, boolean enabled);
    PublicShare publicShare(Long ownedPlan);
}
