package com.playdata.calen.travel.adapter.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.travel.adapter.out.persistence.JpaTravelMapLinkAdapter;
import com.playdata.calen.travel.adapter.out.route.TravelRoutePayloadAdapter;
import com.playdata.calen.travel.application.port.out.TravelMapLinkRuntimePort;
import com.playdata.calen.travel.application.service.TravelMapLinksService;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class TravelMapLinkDifferentialTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test void frozenLegacyWorkflowMatchesDetachedMapClustersPreviewsAndMediaAcrossGeneratedScopes() throws Exception {
        for (int seed = 0; seed < 48; seed++) {
            var plans = mock(TravelPlanRepository.class); var records = mock(TravelExpenseRecordRepository.class);
            var media = mock(TravelMediaAssetRepository.class); var routes = mock(TravelRouteSegmentRepository.class);
            var links = mock(TravelMapShareLinkRepository.class); var storage = mock(TravelMediaStorageService.class);
            var policy = new TravelPhotoClusterPolicy();
            var payload = new TravelRoutePayloadAdapter(json, mock(DataSource.class), storage);
            var oracle = new TravelMapLinkLegacyOracle(links, plans, records, media, routes, json, policy, payload);
            var modern = new TravelMapLinksService(new JpaTravelMapLinkAdapter(mock(AppUserService.class), mock(AppUserRepository.class),
                    links, plans, records, media, routes, json), mock(TravelMapLinkRuntimePort.class), payload, policy);
            var account = new AppUser(); account.setId(7L); account.setLoginId("synthetic-owner"); account.setDisplayName("Synthetic owner");
            var foreign = new AppUser(); foreign.setId(8L);
            var p1 = plan(10L, account); var p2 = plan(11L, account); var p3 = plan(12L, foreign);
            var allPlans = List.of(p1, p2, p3);
            var link = new TravelMapShareLink(); link.setOwner(account); link.setToken("synthetic-canonical-link"); link.setTitle(seed % 3 == 0 ? null : "Title");
            link.setCreatedAt(LocalDateTime.of(2026, 10, 16, 1, 2)); link.setUpdatedAt(link.getCreatedAt());
            link.setPlanIdsJson(seed == 0 ? "not-json" : "[10,12,11,999,0,-1,null,10]");
            link.setExcludedRecordIdsJson(seed % 2 == 0 ? "[101,104]" : "[]");
            link.setExcludedMediaIdsJson(seed % 3 == 0 ? "[203,207]" : "[]");
            link.setExcludedRouteIdsJson(seed % 4 == 0 ? "[301]" : "[]");
            when(links.findByTokenAndActiveTrue(link.getToken())).thenReturn(Optional.of(link));
            when(plans.findAllById(any())).thenAnswer(call -> {
                Collection<Long> ids = call.getArgument(0);
                return allPlans.stream().filter(plan -> ids.contains(plan.getId())).toList();
            });
            var allRecords = new ArrayList<TravelExpenseRecord>(); var allPhotos = new ArrayList<TravelMediaAsset>();
            for (int i = 0; i < 9; i++) {
                var record = new TravelExpenseRecord(); record.setId(100L + i); record.setPlan(allPlans.get(i % 3));
                record.setRecordType(i == 8 ? TravelRecordType.LEDGER : TravelRecordType.MEMORY); record.setTitle("Place " + i); record.setCategory("Place");
                record.setExpenseDate(LocalDate.of(2026, 10, 16 - i % 2)); record.setExpenseTime(i % 2 == 0 ? null : LocalTime.of(12, i));
                record.setLatitude(i == 7 ? null : new BigDecimal("37.50000" + i)); record.setLongitude(i == 7 ? null : new BigDecimal("127.00000" + i));
                record.setAmount(new BigDecimal("12.30")); record.setMemo("Memo " + i);
                allRecords.add(record);
                var asset = new TravelMediaAsset(); asset.setId(200L + i); asset.setPlan(record.getPlan()); asset.setRecord(record); asset.setUploadedBy(account);
                asset.setMediaType(i == 6 ? TravelMediaType.RECEIPT : TravelMediaType.PHOTO);
                asset.setGpsLatitude(i == 7 ? new BigDecimal("36.1") : null); asset.setGpsLongitude(i == 7 ? new BigDecimal("126.1") : null);
                asset.setRepresentativeOverride(seed % 2 == 0 && i == 3); asset.setUploadedAt(LocalDateTime.of(2026, 10, 16, 1, i));
                asset.setOriginalFileName("photo-" + i + ".jpg"); asset.setContentType("image/jpeg"); asset.setFileSize(12L);
                asset.setStoragePath("test-only/photo-" + i); asset.setCaption("Caption " + i); allPhotos.add(asset);
            }
            var extra = new TravelMediaAsset(); extra.setId(210L); extra.setPlan(p1); extra.setRecord(allRecords.get(0)); extra.setUploadedBy(account);
            extra.setMediaType(TravelMediaType.PHOTO); extra.setRepresentativeOverride(seed % 2 == 0); extra.setUploadedAt(LocalDateTime.of(2026, 10, 16, 2, 1));
            extra.setOriginalFileName("extra.jpg"); extra.setContentType("image/jpeg"); extra.setFileSize(13L); extra.setStoragePath("test-only/extra"); allPhotos.add(extra);
            var orderedPhotos = allPhotos.stream().sorted(Comparator.comparing(TravelMediaAsset::getId).reversed()).toList();
            when(records.findAllByPlanIdInAndRecordType(any(), eq(TravelRecordType.MEMORY))).thenAnswer(call -> {
                Collection<Long> ids = call.getArgument(0);
                return allRecords.stream().filter(r -> ids.contains(r.getPlan().getId()) && r.getRecordType() == TravelRecordType.MEMORY).toList();
            });
            when(media.findAllByRecordIdInOrderByUploadedAtDescIdDesc(any())).thenAnswer(call -> {
                Collection<Long> ids = call.getArgument(0);
                return orderedPhotos.stream().filter(a -> ids.contains(a.getRecord().getId())).toList();
            });
            when(media.findAllByPlanIdInAndMediaTypeOrderByUploadedAtDescIdDesc(any(), eq(TravelMediaType.PHOTO))).thenAnswer(call -> {
                Collection<Long> ids = call.getArgument(0);
                return orderedPhotos.stream().filter(a -> ids.contains(a.getPlan().getId()) && a.getMediaType() == TravelMediaType.PHOTO).toList();
            });
            when(media.findById(anyLong())).thenAnswer(call -> allPhotos.stream().filter(a -> a.getId().equals(call.getArgument(0))).findFirst());
            when(media.getReferenceById(anyLong())).thenAnswer(call -> allPhotos.stream().filter(a -> a.getId().equals(call.getArgument(0))).findFirst().orElseThrow());
            var routeRows = new ArrayList<TravelRouteSegment>();
            for (int i = 0; i < 3; i++) {
                var route = new TravelRouteSegment(); route.setId(300L + i); route.setPlan(allPlans.get(i));
                route.setRouteDate(LocalDate.of(2026, 10, 16)); route.setTitle("Route " + i); route.setDurationMinutes(seed % 2 == 0 ? null : i);
                route.setDistanceKm(new BigDecimal("1.2345")); route.setRoutePathJson("[{\"latitude\":37.1234567,\"longitude\":127.1234567,\"label\":\"Private\"},{\"latitude\":37.2234567,\"longitude\":127.2234567}]");
                routeRows.add(route);
            }
            when(routes.findAllByPlanIdInOrderByRouteDateDescIdDesc(any())).thenAnswer(call -> {
                Collection<Long> ids = call.getArgument(0);
                return routeRows.stream().filter(r -> ids.contains(r.getPlan().getId())).toList();
            });
            String token = "  " + link.getToken() + "  ";
            equalJson(oracle.getTravelMapShare(token), TravelMapLinkMapper.map(modern.map(token)));
            equalJson(oracle.getTravelMapShareLoginPreview(token), TravelMapLinkMapper.preview(modern.preview(token)));
            for (var cluster : oracle.getTravelMapShare(token).overview().photoClusters()) {
                equalJson(oracle.getTravelMapSharePhotoCluster(token, cluster.id(), 0, 1, 210L), TravelMapLinkMapper.cluster(modern.cluster(token, cluster.id(), 0, 1, 210L)));
                equalJson(oracle.getTravelMapSharePhotoCluster(token, cluster.id(), 1, 2, null), TravelMapLinkMapper.cluster(modern.cluster(token, cluster.id(), 1, 2, null)));
            }
            for (long id : List.of(200L, 203L, 206L, 207L, 208L, 999L)) compareOutcome(() -> oracle.getTravelMapShareMediaDownload(token, id), () -> modern.media(token, id));
            for (int number : List.of(0, 1, 2, 80, 81)) compareOutcome(() -> oracle.getTravelMapShareLoginPreviewMarkerMediaDownload(token, number), () -> modern.previewMedia(token, number));
            verifyNoInteractions(storage);
        }
    }
    private void equalJson(Object old, Object modern) throws Exception { assertThat(json.writeValueAsString(modern)).isEqualTo(json.writeValueAsString(old)); }
    private void compareOutcome(Supplier<?> old, Supplier<?> modern) throws Exception {
        Object expected;
        try { expected = old.get(); }
        catch (RuntimeException e) { assertThatThrownBy(modern::get).isExactlyInstanceOf(e.getClass()).hasMessage(e.getMessage()); return; }
        equalJson(expected, modern.get());
    }
    private TravelPlan plan(long id, AppUser owner) {
        var plan = new TravelPlan(); plan.setId(id); plan.setOwner(owner); plan.setName("Trip " + id); plan.setColorHex("#abcdef");
        plan.setStatus(TravelPlanStatus.PLANNED); plan.setPublicShared(false); return plan;
    }
}
