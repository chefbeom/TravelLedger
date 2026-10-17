package com.playdata.calen.travel.application.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.in.ManageTravelSharingUseCase;
import com.playdata.calen.travel.application.port.out.TravelSharingStore;
import com.playdata.calen.travel.application.port.out.TravelSummaryInvalidationPort;
import com.playdata.calen.travel.domain.model.TravelSharingData.*;
import com.playdata.calen.travel.domain.policy.TravelSharingPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ManageTravelSharingService implements ManageTravelSharingUseCase {
    private final TravelSharingStore store;
    private final TravelSummaryInvalidationPort summaries;

    public ManageTravelSharingService(TravelSharingStore store, TravelSummaryInvalidationPort summaries) {
        this.store = store;
        this.summaries = summaries;
    }

    @Override
    public Share shareCompletedPlan(Long owner, Long planId, String recipientLogin) {
        var sharer = store.activeOwner(owner);
        var plan = store.ownedPlan(owner, planId);
        TravelSharingPolicy.requireCompleted(plan.status());
        var recipient = store.activeRecipient(TravelSharingPolicy.recipientLogin(recipientLogin))
                .orElseThrow(() -> new NotFoundException("공유할 사용자를 찾을 수 없습니다."));
        if (recipient.id().equals(sharer.id())) {
            throw new BadRequestException("자기 자신에게는 여행 전시를 공유할 수 없습니다.");
        }
        var shareId = store.findShare(planId, recipient.id())
                .orElseGet(() -> store.createShare(sharer.id(), plan.id(), recipient.id()));
        summaries.invalidatePublic();
        return store.share(shareId);
    }

    @Override
    public List<Share> shares(Long owner, Long plan) {
        store.ownedPlan(owner, plan);
        return store.shares(owner, plan);
    }

    @Override
    public void cancelShare(Long owner, Long plan, Long share) {
        store.ownedPlan(owner, plan);
        store.cancelShare(owner, plan, share);
        summaries.invalidatePublic();
    }

    @Override
    public List<Recipient> searchRecipients(Long owner, String query) {
        store.activeOwner(owner);
        return store.searchRecipients(owner, query, 10);
    }

    @Override
    public List<Group> groups(Long owner) {
        store.activeOwner(owner);
        return store.groups(owner);
    }

    @Override
    public Group saveGroup(Long ownerId, String rawName, List<String> logins) {
        var owner = store.activeOwner(ownerId);
        String name = TravelSharingPolicy.groupName(rawName);
        Map<String, Recipient> recipients = new LinkedHashMap<>();
        if (logins != null) {
            for (String raw : logins) {
                String login = TravelSharingPolicy.trimToNull(raw);
                if (login == null) {
                    continue;
                }
                var recipient = store.activeRecipient(login)
                        .orElseThrow(() -> new NotFoundException("공유 그룹 사용자를 찾을 수 없습니다: " + login));
                if (recipient.id().equals(owner.id())) {
                    throw new BadRequestException("자기 자신은 공유 그룹에 넣을 수 없습니다.");
                }
                // Preserve lookup order, then de-duplicate by the account's canonical login, not the raw input.
                recipients.putIfAbsent(recipient.loginId(), recipient);
            }
        }
        if (recipients.isEmpty()) {
            throw new BadRequestException("공유 그룹에 포함할 사용자를 선택해주세요.");
        }
        return store.replaceGroup(owner.id(), name, List.copyOf(recipients.values()));
    }

    @Override
    public void deleteGroup(Long owner, Long group) {
        store.activeOwner(owner);
        store.deleteGroup(owner, group);
    }

    @Override
    public PublicShare publicShare(Long owner, Long planId, Boolean enabled) {
        var plan = store.ownedPlan(owner, planId);
        boolean next = Boolean.TRUE.equals(enabled);
        if (next) {
            TravelSharingPolicy.requireCompleted(plan.status());
        }
        store.setPublicShare(plan.id(), next);
        summaries.invalidateOwner(owner);
        summaries.invalidatePublic();
        return store.publicShare(plan.id());
    }
}
