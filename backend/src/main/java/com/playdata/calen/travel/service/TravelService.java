package com.playdata.calen.travel.service;

import com.playdata.calen.travel.adapter.support.TravelReferenceMapper;
import com.playdata.calen.travel.adapter.support.TravelOwnedPlansMapper;
import com.playdata.calen.ledger.adapter.support.LedgerTravelMapper;
import com.playdata.calen.travel.adapter.support.TravelLedgerReflectionMapper;
import com.playdata.calen.travel.adapter.support.TravelAtlasMapper;
import com.playdata.calen.travel.adapter.support.TravelMapLinkMapper;
import com.playdata.calen.travel.adapter.support.TravelRouteMapper;
import com.playdata.calen.travel.adapter.support.TravelRouteUploadMapper;
import com.playdata.calen.travel.adapter.support.TravelMediaUploadMapper;
import com.playdata.calen.travel.adapter.support.TravelSharingMapper;
import com.playdata.calen.travel.adapter.support.TravelExhibitMapper;
import com.playdata.calen.travel.adapter.support.TravelOwnedMapMapper;
import com.playdata.calen.travel.application.port.in.ReadOwnedTravelMapUseCase;
import com.playdata.calen.travel.adapter.support.TravelPlanDetailMapper;
import com.playdata.calen.travel.application.port.in.ManageOwnedTravelMediaUseCase;
import com.playdata.calen.travel.application.port.in.ManageTravelSharingUseCase;
import com.playdata.calen.travel.application.port.in.ReadTravelExhibitsUseCase;
import com.playdata.calen.travel.application.port.in.ManageTravelRouteFilesUseCase;
import com.playdata.calen.travel.application.port.in.WriteTravelRouteUseCase;
import com.playdata.calen.travel.adapter.support.TravelClusterDetailMapper;
import com.playdata.calen.travel.application.port.in.ManageTravelPhotoClustersUseCase;

import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.travel.adapter.support.TravelBudgetMapper;
import com.playdata.calen.travel.application.port.in.WriteTravelBudgetUseCase;
import com.playdata.calen.travel.adapter.support.TravelPlanMapper;
import com.playdata.calen.travel.adapter.support.TravelRecordMapper;
import com.playdata.calen.travel.application.port.in.WriteTravelRecordUseCase;
import com.playdata.calen.travel.application.port.in.WriteTravelPlanUseCase;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.dto.TravelBudgetItemRequest;
import com.playdata.calen.travel.dto.TravelBudgetItemResponse;
import com.playdata.calen.travel.dto.TravelCategoryCatalogResponse;
import com.playdata.calen.travel.dto.TravelCommunityFeedPageResponse;
import com.playdata.calen.travel.dto.TravelExchangeRateResponse;
import com.playdata.calen.travel.dto.TravelExpenseRecordRequest;
import com.playdata.calen.travel.dto.TravelExpenseRecordResponse;
import com.playdata.calen.travel.dto.TravelMediaResponse;
import com.playdata.calen.travel.dto.TravelMediaUploadCompleteRequest;
import com.playdata.calen.travel.dto.TravelMediaUploadPrepareRequest;
import com.playdata.calen.travel.dto.TravelMediaUploadPrepareResponse;
import com.playdata.calen.travel.dto.TravelMyMapMarkerDetailBundleResponse;
import com.playdata.calen.travel.dto.TravelMyMapOverviewResponse;
import com.playdata.calen.travel.dto.TravelMyMapPhotoClusterPageResponse;
import com.playdata.calen.travel.dto.TravelMemoryRecordRequest;
import com.playdata.calen.travel.dto.TravelMemoryRecordResponse;
import com.playdata.calen.travel.dto.TravelMapShareLinkRequest;
import com.playdata.calen.travel.dto.TravelMapShareLinkResponse;
import com.playdata.calen.travel.dto.TravelLoginMapPreviewResponse;
import com.playdata.calen.travel.dto.TravelPhotoFrameMediaResponse;
import com.playdata.calen.travel.dto.TravelPlanDetailResponse;
import com.playdata.calen.travel.dto.TravelPlanPublicShareResponse;
import com.playdata.calen.travel.dto.TravelPlanRequest;
import com.playdata.calen.travel.dto.TravelPlanShareResponse;
import com.playdata.calen.travel.dto.TravelPlanSummaryResponse;
import com.playdata.calen.travel.dto.TravelPortfolioResponse;
import com.playdata.calen.travel.dto.TravelPublicMapShareResponse;
import com.playdata.calen.travel.dto.TravelPublicTripsOverviewResponse;
import com.playdata.calen.travel.dto.TravelRouteGpxUploadAbortRequest;
import com.playdata.calen.travel.dto.TravelRouteGpxUploadCompleteRequest;
import com.playdata.calen.travel.dto.TravelRouteGpxUploadPrepareRequest;
import com.playdata.calen.travel.dto.TravelRouteGpxUploadPrepareResponse;
import com.playdata.calen.travel.dto.TravelRouteSegmentRequest;
import com.playdata.calen.travel.dto.TravelRouteSegmentResponse;
import com.playdata.calen.travel.dto.TravelShareGroupRequest;
import com.playdata.calen.travel.dto.TravelShareGroupResponse;
import com.playdata.calen.travel.dto.TravelShareRecipientResponse;
import com.playdata.calen.travel.dto.TravelSharedExhibitDetailResponse;
import com.playdata.calen.travel.dto.TravelSharedExhibitPageResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Compatibility facade for internal callers; feature use cases own all business and I/O work. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TravelService {

    private final com.playdata.calen.travel.application.port.in.ReadTravelReferenceUseCase referenceReads;

    private final com.playdata.calen.ledger.application.port.in.ReflectTravelLedgerUseCase ledgerReflection;
    private final WriteTravelBudgetUseCase budgetWrites;
    private final WriteTravelPlanUseCase planWrites;
    private final WriteTravelRecordUseCase recordWrites;
    private final ManageTravelPhotoClustersUseCase clusterDetails;
    private final WriteTravelRouteUseCase routeWrites;
    private final ManageTravelRouteFilesUseCase routeFiles;
    private final ManageOwnedTravelMediaUseCase ownedMedia;
    private final ManageTravelSharingUseCase sharingManagement;
    private final ReadTravelExhibitsUseCase exhibitReads;
    private final ReadOwnedTravelMapUseCase mapReads;
    private final com.playdata.calen.travel.application.port.in.TravelMapLinksUseCase mapLinks;
    private final com.playdata.calen.travel.application.port.in.ReadTravelAtlasUseCase atlasReads;
    private final com.playdata.calen.travel.application.port.in.ReadOwnedTravelPlansUseCase planReads;

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public List<TravelPlanSummaryResponse> getPlans(Long userId) {
        return planReads.plans(userId).stream().map(TravelOwnedPlansMapper::summary).toList();
    }

    public List<TravelPhotoFrameMediaResponse> getPhotoFrameMedia(Long userId) {
        return mapReads.frames(userId).stream().map(TravelOwnedMapMapper::frame).toList();
    }

    public TravelPlanDetailResponse getPlan(Long userId, Long planId) {
        return TravelPlanDetailMapper.response(planReads.detail(userId, planId), id -> "/api/travel/media/" + id + "/content");
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public TravelPortfolioResponse getPortfolio(Long userId) {
        return TravelOwnedPlansMapper.portfolio(planReads.portfolio(userId));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public TravelMyMapOverviewResponse getMyMapOverview(Long userId) {
        return TravelOwnedMapMapper.response(mapReads.overview(userId));
    }

    public TravelMyMapMarkerDetailBundleResponse getMyMapMarkerDetailBundle(Long userId, Long markerId) {
        return TravelOwnedMapMapper.response(mapReads.markerBundle(userId, markerId));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public TravelMyMapPhotoClusterPageResponse getMyMapPhotoClusterDetail(
            Long userId,
            Long clusterId,
            Integer page,
            Integer size,
            Long focusMediaId
    ) {
        return TravelClusterDetailMapper.response(clusterDetails.page(userId, clusterId, page, size, focusMediaId));
    }

    @Transactional
    public TravelMapShareLinkResponse createTravelMapShareLink(Long userId, TravelMapShareLinkRequest request) {
        return TravelMapLinkMapper.link(mapLinks.create(userId, TravelMapLinkMapper.draft(request)));
    }

    public TravelPublicMapShareResponse getTravelMapShare(String token) { return TravelMapLinkMapper.map(mapLinks.map(token)); }

    public TravelLoginMapPreviewResponse getTravelMapShareLoginPreview(String token) { return TravelMapLinkMapper.preview(mapLinks.preview(token)); }

    public MediaDownload getTravelMapShareLoginPreviewMarkerMediaDownload(String token, int number) {
        var download = mapLinks.previewMedia(token, number);
        return new MediaDownload(download.storagePath(), download.contentType(), download.fileName());
    }

    public TravelMyMapPhotoClusterPageResponse getTravelMapSharePhotoCluster(String token, Long cluster, Integer page, Integer size, Long focus) {
        return TravelMapLinkMapper.cluster(mapLinks.cluster(token, cluster, page, size, focus));
    }

    public MediaDownload getTravelMapShareMediaDownload(String token, Long media) {
        var download = mapLinks.media(token, media);
        return new MediaDownload(download.storagePath(), download.contentType(), download.fileName());
    }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public TravelMyMapPhotoClusterPageResponse updateMyMapPhotoClusterRepresentative(Long userId, Long clusterId, Long mediaId) {
        return TravelClusterDetailMapper.response(clusterDetails.changeRepresentative(userId, clusterId, mediaId));
    }

    public TravelCategoryCatalogResponse getCategoryCatalog(Long userId) {
        return TravelReferenceMapper.catalog(referenceReads.catalog(userId));
    }

    public TravelCommunityFeedPageResponse getCommunityFeed(Long userId, Integer page, Integer size) {
        return TravelAtlasMapper.community(atlasReads.community(userId, page, size));
    }

    public TravelPublicTripsOverviewResponse getPublicTripsOverview(Long userId) {
        return TravelAtlasMapper.overview(atlasReads.overview(userId));
    }

    public TravelMyMapPhotoClusterPageResponse getPublicTripPhotoClusterDetail(Long userId, Long cluster, Integer page, Integer size, Long focus) {
        return TravelAtlasMapper.cluster(atlasReads.cluster(userId, cluster, page, size, focus));
    }

    @Transactional
    public TravelPlanShareResponse shareCompletedPlan(Long userId, Long planId, String recipientLoginIdRaw) {
        return TravelSharingMapper.share(sharingManagement.shareCompletedPlan(userId, planId, recipientLoginIdRaw));
    }

    public List<TravelPlanShareResponse> getPlanShares(Long userId, Long planId) {
        return sharingManagement.shares(userId, planId).stream().map(TravelSharingMapper::share).toList();
    }

    @Transactional
    public void cancelPlanShare(Long userId, Long planId, Long shareId) {
        sharingManagement.cancelShare(userId, planId, shareId);
    }

    public List<TravelShareRecipientResponse> searchShareRecipients(Long userId, String query) {
        return sharingManagement.searchRecipients(userId, query).stream().map(TravelSharingMapper::recipient).toList();
    }

    public List<TravelShareGroupResponse> getShareGroups(Long userId) {
        return sharingManagement.groups(userId).stream().map(TravelSharingMapper::group).toList();
    }

    @Transactional
    public TravelShareGroupResponse saveShareGroup(Long userId, TravelShareGroupRequest request) {
        return TravelSharingMapper.group(sharingManagement.saveGroup(userId, request.name(), request.recipientLoginIds()));
    }

    @Transactional
    public void deleteShareGroup(Long userId, Long groupId) {
        sharingManagement.deleteGroup(userId, groupId);
    }

    @Transactional
    public TravelPlanPublicShareResponse updatePlanPublicShare(Long userId, Long planId, Boolean publicShared) {
        return TravelSharingMapper.publicShare(sharingManagement.publicShare(userId, planId, publicShared));
    }

    public TravelSharedExhibitPageResponse getSharedExhibits(Long userId, Integer page, Integer size) {
        return TravelExhibitMapper.page(exhibitReads.received(userId, page, size));
    }

    public TravelSharedExhibitDetailResponse getSharedExhibit(Long userId, Long shareId) {
        return TravelExhibitMapper.received(exhibitReads.receivedDetail(userId, shareId));
    }

    // Caller must verify an accepted, current group share before using these methods.
    public TravelSharedExhibitDetailResponse getRecordSharedPlanDetail(Long ownerId, Long planId, Long shareId) {
        return TravelExhibitMapper.recordShare(exhibitReads.recordShareDetail(ownerId, planId, shareId));
    }

    public MediaDownload getRecordSharedMediaDownload(Long ownerId, Long planId, Long mediaId) {
        var download = exhibitReads.recordShareMedia(ownerId, planId, mediaId);
        return new MediaDownload(download.storagePath(), download.contentType(), download.fileName());
    }

    @Transactional
    public TravelPlanSummaryResponse createPlan(Long userId, TravelPlanRequest request) {
        return TravelPlanMapper.response(planWrites.create(userId, TravelPlanMapper.draft(request)));
    }

    @Transactional
    public TravelPlanSummaryResponse updatePlan(Long userId, Long planId, TravelPlanRequest request) {
        return TravelPlanMapper.response(planWrites.update(userId, planId, TravelPlanMapper.draft(request)));
    }

    @Transactional
    public void deletePlan(Long userId, Long planId) {
        planWrites.delete(userId, planId);
    }

    @Transactional
    public TravelBudgetItemResponse createBudgetItem(Long userId, Long planId, TravelBudgetItemRequest request) {
        return TravelBudgetMapper.response(budgetWrites.create(userId, planId, TravelBudgetMapper.draft(request)));
    }

    @Transactional
    public TravelBudgetItemResponse updateBudgetItem(Long userId, Long itemId, TravelBudgetItemRequest request) {
        return TravelBudgetMapper.response(budgetWrites.update(userId, itemId, TravelBudgetMapper.draft(request)));
    }

    @Transactional
    public void deleteBudgetItem(Long userId, Long itemId) {
        budgetWrites.delete(userId, itemId);
    }

    @Transactional
    public TravelExpenseRecordResponse createExpenseRecord(Long userId, Long planId, TravelExpenseRecordRequest request) {
        return TravelRecordMapper.expense(recordWrites.createExpense(userId, planId, TravelRecordMapper.draft(request)));
    }

    @Transactional
    public TravelExpenseRecordResponse updateExpenseRecord(Long userId, Long recordId, TravelExpenseRecordRequest request) {
        return TravelRecordMapper.expense(recordWrites.updateExpense(userId, recordId, TravelRecordMapper.draft(request)));
    }

    @Transactional
    public void deleteExpenseRecord(Long userId, Long recordId) {
        recordWrites.deleteExpense(userId, recordId);
    }

    @Transactional
    public LedgerEntryResponse reflectExpenseRecordToLedger(Long userId, Long recordId) {
        return LedgerTravelMapper.response(ledgerReflection.toLedger(userId, recordId));
    }

    @Transactional
    public TravelExpenseRecordResponse reflectLedgerEntryToTravelRecord(Long userId, Long planId, Long entryId) {
        return TravelLedgerReflectionMapper.response(ledgerReflection.toTravel(userId, planId, entryId));
    }

    @Transactional
    public TravelMemoryRecordResponse createMemoryRecord(Long userId, Long planId, TravelMemoryRecordRequest request) {
        return TravelRecordMapper.memory(recordWrites.createMemory(userId, planId, TravelRecordMapper.draft(request)));
    }

    @Transactional
    public TravelMemoryRecordResponse updateMemoryRecord(Long userId, Long memoryId, TravelMemoryRecordRequest request) {
        return TravelRecordMapper.memory(recordWrites.updateMemory(userId, memoryId, TravelRecordMapper.draft(request)));
    }

    @Transactional
    public void deleteMemoryRecord(Long userId, Long memoryId) {
        recordWrites.deleteMemory(userId, memoryId);
    }

    @Transactional
    public TravelRouteSegmentResponse createRouteSegment(Long userId, Long planId, TravelRouteSegmentRequest request) {
        return TravelRouteMapper.response(routeWrites.create(userId,planId,TravelRouteMapper.draft(request)));
    }

    @Transactional
    public TravelRouteSegmentResponse updateRouteSegment(Long userId, Long routeId, TravelRouteSegmentRequest request) {
        return TravelRouteMapper.response(routeWrites.update(userId,routeId,TravelRouteMapper.draft(request)));
    }

    @Transactional
    public TravelRouteSegmentResponse uploadRouteGpxFiles(Long userId, Long routeId, List<MultipartFile> files) {
        return TravelRouteMapper.response(routeFiles.upload(userId,routeId,TravelRouteUploadMapper.uploads(files)));
    }

    public TravelRouteGpxUploadPrepareResponse prepareRouteGpxUpload(Long userId,Long routeId,TravelRouteGpxUploadPrepareRequest request) {
        return TravelRouteUploadMapper.response(routeFiles.prepare(userId,routeId,TravelRouteUploadMapper.candidates(request)));
    }

    @Transactional
    public TravelRouteSegmentResponse completeRouteGpxUpload(Long userId,Long routeId,TravelRouteGpxUploadCompleteRequest request) {
        return TravelRouteMapper.response(routeFiles.complete(userId,routeId,TravelRouteUploadMapper.completed(request)));
    }

    public void abortRouteGpxUpload(Long userId,Long routeId,TravelRouteGpxUploadAbortRequest request) {
        routeFiles.abort(userId,routeId,request.objectKeys());
    }
    @Transactional
    public void deleteRouteSegment(Long userId, Long routeId) {
        routeWrites.delete(userId,routeId);
    }

    public TravelMediaUploadPrepareResponse prepareRecordMediaUpload(Long userId, Long recordId, TravelMediaUploadPrepareRequest request) {
        return TravelMediaUploadMapper.response(ownedMedia.prepare(userId, recordId, TravelRecordType.LEDGER,
                TravelMediaUploadMapper.candidates(request)));
    }

    @Transactional
    public List<TravelMediaResponse> completeRecordMediaUpload(Long userId, Long recordId, TravelMediaUploadCompleteRequest request) {
        return TravelMediaUploadMapper.responses(ownedMedia.complete(userId, recordId, TravelRecordType.LEDGER,
                request.mediaType(), request.caption(), TravelMediaUploadMapper.completed(request)));
    }

    public TravelMediaUploadPrepareResponse prepareMemoryMediaUpload(Long userId, Long memoryId, TravelMediaUploadPrepareRequest request) {
        return TravelMediaUploadMapper.response(ownedMedia.prepare(userId, memoryId, TravelRecordType.MEMORY,
                TravelMediaUploadMapper.candidates(request)));
    }

    @Transactional
    public List<TravelMediaResponse> completeMemoryMediaUpload(Long userId, Long memoryId, TravelMediaUploadCompleteRequest request) {
        return TravelMediaUploadMapper.responses(ownedMedia.complete(userId, memoryId, TravelRecordType.MEMORY,
                request.mediaType(), request.caption(), TravelMediaUploadMapper.completed(request)));
    }

    @Transactional
    public void deleteMedia(Long userId, Long mediaId) {
        ownedMedia.delete(userId, mediaId);
    }

    public MediaDownload getMediaDownload(Long userId, Long mediaId) {
        var download = ownedMedia.download(userId, mediaId);
        return new MediaDownload(download.storagePath(), download.contentType(), download.fileName());
    }

    public MediaDownload getSharedMediaDownload(Long mediaId, String token) {
        var download = exhibitReads.publicMedia(mediaId, token);
        return new MediaDownload(download.storagePath(), download.contentType(), download.fileName());
    }

    public MediaDownload getSharedExhibitMediaDownload(Long userId, Long shareId, Long mediaId) {
        var download = exhibitReads.receivedMedia(userId, shareId, mediaId);
        return new MediaDownload(download.storagePath(), download.contentType(), download.fileName());
    }

    public List<TravelExchangeRateResponse> getExchangeRates(Long userId, String currencies) {
        return referenceReads.rates(userId, currencies).stream().map(TravelReferenceMapper::quote).toList();
    }

    public record MediaDownload(String storagePath, String contentType, String fileName) {
    }
}
