package com.playdata.calen.travel.adapter.out.persistence;

import static com.playdata.calen.travel.adapter.support.TravelPlanDetailMapper.*;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.adapter.support.TravelPlanDetailMapper;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelExhibitData.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.Download;
import com.playdata.calen.travel.domain.model.TravelPlanDetailData;
import com.playdata.calen.travel.domain.policy.*;
import com.playdata.calen.travel.repository.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaTravelExhibitReadAdapter implements TravelExhibitReadStore {
    private final AppUserService users;
    private final TravelPlanRepository plans;
    private final TravelPlanShareRepository shares;
    private final TravelBudgetItemRepository budgets;
    private final TravelExpenseRecordRepository records;
    private final TravelMediaAssetRepository media;
    private final TravelRouteSegmentRepository routes;
    private final TravelRoutePayloadPort payload;

    @Override public void requireActiveViewer(Long viewer) { users.getRequiredUser(viewer); }
    @Override public Page completedShares(Long viewer, int page, int size) {
        var result = shares.findAllByRecipientIdAndPlan_StatusOrderByCreatedAtDescIdDesc(viewer, TravelPlanStatus.COMPLETED, PageRequest.of(page, size));
        return new Page(result.getContent().stream().map(this::summary).toList(), page, size, result.getTotalElements(), result.getTotalPages());
    }
    @Override public Access receivedShare(Long viewer, Long id) {
        var share = shares.findByIdAndRecipientId(id, viewer).orElseThrow(() -> new NotFoundException("공유된 여행 전시를 찾을 수 없습니다."));
        return new Access(share.getId(), plan(share.getPlan()));
    }
    @Override public Plan ownedPlan(Long owner, Long id) {
        return plan(plans.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Travel plan not found.")));
    }
    @Override public TravelPlanDetailData.Detail planDetail(Long owner, Long id) {
        var plan = plans.getReferenceById(id);
        var budgetItems = budgets.findAllByPlanIdAndPlanOwnerIdOrderByDisplayOrderAscIdAsc(id, owner).stream().sorted(BUDGET_ORDER).toList();
        var ledger = records.findAllByPlanIdAndPlanOwnerIdOrderByExpenseDateDescIdDesc(id, owner).stream()
                .filter(record -> TravelRecordPolicy.matches(record.getRecordType(), TravelRecordType.LEDGER)).sorted(RECORD_ORDER).toList();
        var memories = records.findAllByPlanIdAndPlanOwnerIdOrderByExpenseDateDescIdDesc(id, owner).stream()
                .filter(record -> TravelRecordPolicy.matches(record.getRecordType(), TravelRecordType.MEMORY)).sorted(RECORD_ORDER).toList();
        var pictures = media.findAllByPlanIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(id, owner).stream().sorted(MEDIA_ORDER).toList();
        var paths = routes.findAllByPlanIdAndPlanOwnerIdOrderByRouteDateDescIdDesc(id, owner).stream().sorted(ROUTE_ORDER).toList();
        return snapshot(plan, budgetItems, ledger, memories, pictures, paths, payload);
    }
    @Override public Attribution shareAttribution(Long id) { return attribution(shares.getReferenceById(id)); }
    @Override public Attribution ownerAttribution(Long id) {
        var owner = plans.getReferenceById(id).getOwner();
        return new Attribution(owner.getLoginId(), owner.getDisplayName(), null);
    }
    @Override public Optional<Media> media(Long id) {
        return media.findById(id).map(asset -> new Media(asset.getPlan().getId(), download(asset)));
    }
    @Override public Optional<PublicMedia> publicMedia(Long id) {
        return media.findById(id).map(asset -> {
            var record = asset.getRecord();
            var type = record.getRecordType();
            boolean photo = TravelExhibitPolicy.memoryPhoto(type, asset.getMediaType());
            boolean community = photo && Boolean.TRUE.equals(record.getSharedWithCommunity());
            // Preserve short-circuiting: denied non-photos and community photos do not initialize plan visibility.
            boolean publicPlan = photo && !community && Boolean.TRUE.equals(asset.getPlan().getPublicShared());
            var status = publicPlan ? asset.getPlan().getStatus() : null;
            return new PublicMedia(type, asset.getMediaType(), community, publicPlan, status, download(asset));
        });
    }

    private Plan plan(TravelPlan plan) { return new Plan(plan.getId(), plan.getOwner().getId(), plan.getStatus()); }
    private Attribution attribution(TravelPlanShare share) {
        return new Attribution(share.getSharedBy().getLoginId(), share.getSharedBy().getDisplayName(), share.getCreatedAt());
    }
    private Download download(TravelMediaAsset asset) { return new Download(asset.getStoragePath(), asset.getContentType(), asset.getOriginalFileName()); }
    private Summary summary(TravelPlanShare share) {
        var plan = share.getPlan();
        var owner = plan.getOwner().getId();
        var memories = records.findAllByPlanIdAndPlanOwnerIdOrderByExpenseDateDescIdDesc(plan.getId(), owner).stream()
                .filter(record -> TravelRecordPolicy.matches(record.getRecordType(), TravelRecordType.MEMORY)).sorted(RECORD_ORDER).toList();
        var pictures = media.findAllByPlanIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(plan.getId(), owner).stream().sorted(MEDIA_ORDER).toList();
        var paths = routes.findAllByPlanIdAndPlanOwnerIdOrderByRouteDateDescIdDesc(plan.getId(), owner).stream().sorted(ROUTE_ORDER).toList();
        return new Summary(share.getId(), plan.getId(), plan.getName(), plan.getDestination(), plan.getStartDate(), plan.getEndDate(),
                TravelPlanPolicy.status(plan.getStatus()), TravelPlanPolicy.color(plan.getColorHex()), attribution(share), memories.size(), paths.size(), pictures.size());
    }
}
