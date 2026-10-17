package com.playdata.calen.travel.adapter.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.travel.adapter.out.persistence.JpaTravelAtlasAdapter;
import com.playdata.calen.travel.adapter.out.route.TravelRoutePayloadAdapter;
import com.playdata.calen.travel.adapter.out.security.TravelPublicMediaTokenAdapter;
import com.playdata.calen.travel.application.service.ReadTravelAtlasService;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;

class TravelAtlasDifferentialTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test void frozenAtlasAndCommunityJsonMatchesForPublicPrecedencePrivateRecipientsGpsCountsPagesAndFullRoutes() throws Exception {
        for (int seed = 0; seed < 48; seed++) {
            var users = mock(AppUserService.class); var plans = mock(TravelPlanRepository.class); var shares = mock(TravelPlanShareRepository.class);
            var records = mock(TravelExpenseRecordRepository.class); var media = mock(TravelMediaAssetRepository.class); var routes = mock(TravelRouteSegmentRepository.class);
            var tokens = mock(TravelPublicMediaTokenService.class); var storage = mock(TravelMediaStorageService.class);
            when(tokens.issueToken(anyLong())).thenAnswer(call -> "fixture-token-" + call.getArgument(0));
            var payload = new TravelRoutePayloadAdapter(json, mock(DataSource.class), storage); var clustering = new TravelPhotoClusterPolicy();
            var oracle = new TravelAtlasLegacyOracle(users, plans, records, media, routes, shares, tokens, clustering, payload);
            var modern = new ReadTravelAtlasService(new JpaTravelAtlasAdapter(users, plans, shares, records, media, routes), new TravelPublicMediaTokenAdapter(tokens), payload, clustering);
            var owner = user(7L, "Owner"); var uploader = user(8L, "Uploader");
            var allPlans = new ArrayList<TravelPlan>();
            for (int i = 0; i < 4; i++) {
                var plan = new TravelPlan(); plan.setId(10L + i); plan.setOwner(owner); plan.setName("Trip " + i); plan.setColorHex(i % 2 == 0 ? "#abcdef" : "#123456");
                plan.setDestination(i % 2 == 0 ? null : "Destination"); plan.setStartDate(LocalDate.of(2026, 10, 16)); plan.setEndDate(plan.getStartDate().plusDays(1));
                plan.setStatus(i == 3 ? TravelPlanStatus.PLANNED : TravelPlanStatus.COMPLETED); plan.setPublicShared(i < 3 && (seed & (1 << i)) != 0);
                plan.setPublicSharedAt(LocalDateTime.of(2026, 10, 16, 1, i)); allPlans.add(plan);
            }
            var published = allPlans.stream().filter(p -> p.getStatus() == TravelPlanStatus.COMPLETED && Boolean.TRUE.equals(p.getPublicShared())).sorted(Comparator.comparing(TravelPlan::getId).reversed()).toList();
            var received = new ArrayList<TravelPlanShare>();
            if ((seed & 8) != 0) { received.add(share(100L, allPlans.get(1), 5)); received.add(share(101L, allPlans.get(1), 4)); }
            if ((seed & 16) != 0) received.add(share(102L, allPlans.get(2), 3));
            if ((seed & 32) != 0) received.add(share(103L, allPlans.get(0), 2));
            when(plans.findAllByPublicSharedTrueAndStatusOrderByPublicSharedAtDescStartDateDescIdDesc(TravelPlanStatus.COMPLETED)).thenReturn(published);
            when(shares.findAllByRecipientIdAndPlan_StatusOrderByCreatedAtDescIdDesc(99L, TravelPlanStatus.COMPLETED)).thenReturn(received);
            var allRecords = new ArrayList<TravelExpenseRecord>(); var allPhotos = new ArrayList<TravelMediaAsset>();
            for (int i = 0; i < 10; i++) {
                var record = new TravelExpenseRecord(); record.setId(200L + i); record.setPlan(allPlans.get(i % 4)); record.setRecordType(i == 8 ? TravelRecordType.LEDGER : TravelRecordType.MEMORY);
                record.setTitle("Memory " + i); record.setCategory("Place"); record.setMemo(i % 2 == 0 ? null : "Memo");
                record.setExpenseDate(LocalDate.of(2026, 10, 16 - i % 2)); record.setExpenseTime(i % 2 == 0 ? null : LocalTime.of(12, i));
                record.setLatitude(i == 7 || i == 9 ? null : new BigDecimal("37.50000" + i)); record.setLongitude(i == 7 || i == 9 ? null : new BigDecimal("127.00000" + i));
                allRecords.add(record);
                var asset = new TravelMediaAsset(); asset.setId(300L + i); asset.setPlan(record.getPlan()); asset.setRecord(record); asset.setUploadedBy(uploader);
                asset.setMediaType(i == 6 ? TravelMediaType.RECEIPT : TravelMediaType.PHOTO); asset.setUploadedAt(i == 3 ? null : LocalDateTime.of(2026, 10, 16, 1, i));
                asset.setGpsLatitude(i == 7 ? new BigDecimal("36.1234567") : null); asset.setGpsLongitude(i == 7 ? new BigDecimal("126.1234567") : null);
                asset.setRepresentativeOverride(seed % 2 != 0 && i == 0);
                asset.setFileSize(12L); asset.setCaption("Caption " + i); asset.setContentType("image/jpeg"); asset.setOriginalFileName("photo-" + i + ".jpg"); allPhotos.add(asset);
            }
            var extra = new TravelMediaAsset(); extra.setId(310L); extra.setPlan(allPlans.get(0)); extra.setRecord(allRecords.get(0)); extra.setUploadedBy(uploader);
            extra.setMediaType(TravelMediaType.PHOTO); extra.setUploadedAt(LocalDateTime.of(2026, 10, 16, 2, 0)); extra.setRepresentativeOverride(seed % 2 == 0);
            extra.setGpsLatitude(new BigDecimal("37.50002")); extra.setGpsLongitude(new BigDecimal("127.00002"));
            extra.setOriginalFileName("extra.jpg"); extra.setContentType("image/jpeg"); extra.setFileSize(13L); allPhotos.add(extra);
            var ordered = allPhotos.stream().sorted(Comparator.comparing(TravelMediaAsset::getId).reversed()).toList();
            when(records.findAllByPlanIdInAndRecordType(any(), eq(TravelRecordType.MEMORY))).thenAnswer(call -> {
                Collection<Long> ids = call.getArgument(0); return allRecords.stream().filter(r -> ids.contains(r.getPlan().getId()) && r.getRecordType() == TravelRecordType.MEMORY).toList();
            });
            when(media.findAllByPlanIdInAndMediaTypeOrderByUploadedAtDescIdDesc(any(), eq(TravelMediaType.PHOTO))).thenAnswer(call -> {
                Collection<Long> ids = call.getArgument(0); return ordered.stream().filter(a -> ids.contains(a.getPlan().getId()) && a.getMediaType() == TravelMediaType.PHOTO).toList();
            });
            when(media.findAllByRecordIdInOrderByUploadedAtDescIdDesc(any())).thenAnswer(call -> {
                Collection<Long> ids = call.getArgument(0); return ordered.stream().filter(a -> ids.contains(a.getRecord().getId())).toList();
            });
            when(media.getReferenceById(anyLong())).thenAnswer(call -> allPhotos.stream().filter(a -> a.getId().equals(call.getArgument(0))).findFirst().orElseThrow());
            when(records.findCommunityMemoryPage(eq(TravelRecordType.MEMORY), eq(TravelMediaType.PHOTO), any())).thenAnswer(call ->
                    new PageImpl<>(allRecords, call.getArgument(2, Pageable.class), 63));
            var allRoutes = new ArrayList<TravelRouteSegment>();
            for (int i = 0; i < 4; i++) {
                var route = new TravelRouteSegment(); route.setId(400L + i); route.setPlan(allPlans.get(i)); route.setRouteDate(LocalDate.of(2026, 10, 16)); route.setTitle("Route " + i);
                route.setDistanceKm(new BigDecimal("1.2345")); route.setDurationMinutes(seed % 2 == 0 ? null : i);
                route.setRoutePathJson("[{\"latitude\":37.5,\"longitude\":127,\"linkedMemoryId\":200,\"label\":\"Start\"},{\"latitude\":37.6,\"longitude\":127.1}]"); allRoutes.add(route);
            }
            when(routes.findAllByPlanIdInOrderByRouteDateDescIdDesc(any())).thenAnswer(call -> {
                Collection<Long> ids = call.getArgument(0); return allRoutes.stream().filter(r -> ids.contains(r.getPlan().getId())).toList();
            });
            var old = oracle.getPublicTripsOverview(99L);
            equalJson(old, TravelAtlasMapper.overview(modern.overview(99L)));
            for (var cluster : old.photoClusters()) {
                equalJson(oracle.getPublicTripPhotoClusterDetail(99L, cluster.id(), 0, 1, 300L), TravelAtlasMapper.cluster(modern.cluster(99L, cluster.id(), 0, 1, 300L)));
                equalJson(oracle.getPublicTripPhotoClusterDetail(99L, cluster.id(), 1, 2, null), TravelAtlasMapper.cluster(modern.cluster(99L, cluster.id(), 1, 2, null)));
            }
            equalJson(oracle.getCommunityFeed(99L, null, null), TravelAtlasMapper.community(modern.community(99L, null, null)));
            equalJson(oracle.getCommunityFeed(99L, -1, 99), TravelAtlasMapper.community(modern.community(99L, -1, 99)));
            compareOutcome(() -> oracle.getPublicTripPhotoClusterDetail(99L, Long.MAX_VALUE, 0, 12, null), () -> modern.cluster(99L, Long.MAX_VALUE, 0, 12, null));
            verifyNoInteractions(storage);
        }
    }
    private void equalJson(Object old, Object modern) throws Exception { assertThat(json.writeValueAsString(modern)).isEqualTo(json.writeValueAsString(old)); }
    private void compareOutcome(Supplier<?> old, Supplier<?> modern) {
        RuntimeException expected = catchThrowableOfType(old::get, RuntimeException.class);
        assertThat(expected).isNotNull(); assertThatThrownBy(modern::get).isExactlyInstanceOf(expected.getClass()).hasMessage(expected.getMessage());
    }
    private AppUser user(Long id, String display) { var user = new AppUser(); user.setId(id); user.setLoginId("fixture-" + id); user.setDisplayName(display); return user; }
    private TravelPlanShare share(Long id, TravelPlan plan, int minute) {
        var share = new TravelPlanShare(); share.setId(id); share.setPlan(plan); share.setCreatedAt(LocalDateTime.of(2026, 10, 16, 2, minute)); return share;
    }
}
