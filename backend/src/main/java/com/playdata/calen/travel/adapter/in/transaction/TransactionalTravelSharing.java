package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.ManageTravelSharingUseCase;
import com.playdata.calen.travel.application.port.out.TravelSharingStore;
import com.playdata.calen.travel.application.port.out.TravelSummaryInvalidationPort;
import com.playdata.calen.travel.application.service.ManageTravelSharingService;
import com.playdata.calen.travel.domain.model.TravelSharingData.*;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class TransactionalTravelSharing implements ManageTravelSharingUseCase {
    private final ManageTravelSharingUseCase delegate;

    public TransactionalTravelSharing(TravelSharingStore store, TravelSummaryInvalidationPort summaries) {
        delegate = new ManageTravelSharingService(store, summaries);
    }

    @Override
    @Transactional
    public Share shareCompletedPlan(Long owner, Long plan, String recipient) {
        return delegate.shareCompletedPlan(owner, plan, recipient);
    }

    @Override
    public List<Share> shares(Long owner, Long plan) {
        return delegate.shares(owner, plan);
    }

    @Override
    @Transactional
    public void cancelShare(Long owner, Long plan, Long share) {
        delegate.cancelShare(owner, plan, share);
    }

    @Override
    public List<Recipient> searchRecipients(Long owner, String query) {
        return delegate.searchRecipients(owner, query);
    }

    @Override
    public List<Group> groups(Long owner) {
        return delegate.groups(owner);
    }

    @Override
    @Transactional
    public Group saveGroup(Long owner, String name, List<String> recipients) {
        return delegate.saveGroup(owner, name, recipients);
    }

    @Override
    @Transactional
    public void deleteGroup(Long owner, Long group) {
        delegate.deleteGroup(owner, group);
    }

    @Override
    @Transactional
    public PublicShare publicShare(Long owner, Long plan, Boolean enabled) {
        return delegate.publicShare(owner, plan, enabled);
    }
}
