package com.playdata.calen.travel.adapter.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.travel.adapter.out.cache.*;
import com.playdata.calen.travel.adapter.out.persistence.JpaOwnedTravelPlansAdapter;
import com.playdata.calen.travel.application.port.out.TravelRoutePayloadPort;
import com.playdata.calen.travel.application.service.ReadOwnedTravelPlansService;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import com.playdata.calen.travel.dto.*;
import com.playdata.calen.travel.fixture.OwnedTravelPlansLegacyOracle;
import com.playdata.calen.travel.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OwnedTravelPlansDifferentialTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Test void listDetailAndPortfolioWireValuesMatchTheFrozenImplementationAcross48Inputs() throws Exception {
        for (int seed = 0; seed < 48; seed++) {
            var f = new Fixture(seed);
            equal(f.old.getPlans(7L), f.modern.plans(7L).stream().map(TravelOwnedPlansMapper::summary).toList());
            equal(f.old.getPortfolio(7L), TravelOwnedPlansMapper.portfolio(f.modern.portfolio(7L)));
            equal(f.old.getPlan(7L, 1L), TravelPlanDetailMapper.response(f.modern.detail(7L, 1L), id -> "/api/travel/media/" + id + "/content"));
            verify(f.payload, never()).decode(any());
        }
    }
    @Test void cachedPortfolioRoundTripPreservesOpaqueUrlsNullsUnknownDisplayValuesAndNullCollections() throws Exception {
        for (int seed = 0; seed < 48; seed++) {
            var f = new Fixture(seed); var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(f.old.getPortfolio(7L));
            var plan = (com.fasterxml.jackson.databind.node.ObjectNode) node.path("plans").get(0);
            plan.put("status", "old-status"); plan.put("colorHex", "old-color"); plan.putNull("plannedTotalKrw");
            var picture = (com.fasterxml.jackson.databind.node.ObjectNode) node.path("mediaItems").get(0);
            if (seed % 2 == 0) picture.putNull("contentUrl"); else picture.put("contentUrl", "/old-cache/opaque?thumbnail=true");
            picture.putNull("representativeOverride");
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("memoryRecords").get(0)).putNull("sharedWithCommunity");
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("routeSegments").get(0)).putNull("points");
            for (var field : List.of("plans", "budgetItems", "records", "memoryRecords", "mediaItems", "routeSegments")) ((com.fasterxml.jackson.databind.node.ArrayNode) node.path(field)).addNull();
            if ((seed & 1) != 0) node.putNull("budgetItems");
            if ((seed & 2) != 0) node.putNull("records");
            if ((seed & 4) != 0) node.putNull("routeSegments");
            if ((seed & 8) != 0) node.putNull("mediaItems");
            if ((seed & 16) != 0) node.putNull("plans");
            var wire = json.treeToValue(node, TravelPortfolioResponse.class);
            equal(wire, TravelOwnedPlansMapper.portfolio(TravelOwnedPlansMapper.portfolio(wire)));
        }
    }
    void equal(Object expected, Object actual) throws Exception { assertThat(json.writeValueAsString(actual)).isEqualTo(json.writeValueAsString(expected)); }

    static final class Fixture {
        final AppUserService users = mock(AppUserService.class);
        final TravelPlanRepository plans = mock(TravelPlanRepository.class);
        final TravelBudgetItemRepository budgets = mock(TravelBudgetItemRepository.class);
        final TravelExpenseRecordRepository records = mock(TravelExpenseRecordRepository.class);
        final TravelMediaAssetRepository media = mock(TravelMediaAssetRepository.class);
        final TravelRouteSegmentRepository routes = mock(TravelRouteSegmentRepository.class);
        final RedisCacheService redis = mock(RedisCacheService.class);
        final TravelRoutePayloadPort payload = mock(TravelRoutePayloadPort.class);
        final OwnedTravelPlansLegacyOracle old;
        final ReadOwnedTravelPlansService modern;
        Fixture(int seed) {
            var owner = new AppUser(); owner.setId(7L); owner.setLoginId("owner"); owner.setDisplayName("Owner");
            var heads = new ArrayList<TravelPlan>();
            for (int i = 0; i < 2; i++) {
                var p = new TravelPlan(); p.setId(1L + i); p.setOwner(owner); p.setName("Trip " + i); p.setDestination(i == 0 ? null : "Destination"); p.setHomeCurrency("jpy"); p.setHeadCount(seed % 3 + 1);
                p.setStatus(seed % 2 == 0 ? null : TravelPlanStatus.COMPLETED); p.setColorHex(seed % 3 == 0 ? null : "#abcdef"); p.setMemo(" raw memo ");
                p.setStartDate(LocalDate.of(2026, 10, 1 + i)); p.setEndDate(LocalDate.of(2026, 10, 3 + i)); p.setPublicShared(seed % 2 == 0); heads.add(p);
                when(plans.findByIdAndOwnerId(p.getId(), 7L)).thenReturn(Optional.of(p));
            }
            Collections.reverse(heads); when(plans.findAllByOwnerIdOrderByStartDateDescIdDesc(7L)).thenReturn(heads);
            var b = new ArrayList<TravelBudgetItem>(); var r = new ArrayList<TravelExpenseRecord>(); var pictures = new ArrayList<TravelMediaAsset>(); var paths = new ArrayList<TravelRouteSegment>();
            for (int i = 0; i < 6; i++) {
                var p = heads.get(i % 2);
                var budget = new TravelBudgetItem(); budget.setId(10L + i); budget.setPlan(p); budget.setCategory("Budget"); budget.setTitle("Budget " + i); budget.setDisplayOrder(i % 3 == 0 ? null : i % 2);
                budget.setAmount(new BigDecimal("1.2345")); budget.setAmountKrw(i == 4 ? null : new BigDecimal("1.005")); budget.setCurrencyCode("jpy"); budget.setExchangeRateToKrw(new BigDecimal("9.123456")); b.add(budget);
                var record = new TravelExpenseRecord(); record.setId(20L + i); record.setPlan(p); record.setRecordType(i % 3 == 0 ? null : i % 3 == 1 ? TravelRecordType.MEMORY : TravelRecordType.LEDGER);
                record.setExpenseDate(p.getStartDate()); record.setExpenseTime(i % 2 == 0 ? null : LocalTime.of(10, i)); record.setCategory("Place"); record.setTitle("Record " + i); record.setMemo(i % 2 == 0 ? null : " memo ");
                record.setAmount(new BigDecimal("2.3456")); record.setAmountKrw(i == 4 ? null : new BigDecimal("2.005")); record.setCurrencyCode("JPY"); record.setCountry("KR"); record.setRegion("Region"); record.setPlaceName("Place");
                record.setLatitude(i % 2 == 0 ? null : new BigDecimal("37.5555555")); record.setLongitude(i % 2 == 0 ? null : new BigDecimal("127.1234567")); record.setSharedWithCommunity(seed % 3 == 0 ? null : seed % 2 == 0); r.add(record);
                var asset = new TravelMediaAsset(); asset.setId(30L + i); asset.setPlan(p); asset.setRecord(record); asset.setUploadedBy(owner); asset.setMediaType(i % 2 == 0 ? TravelMediaType.RECEIPT : TravelMediaType.PHOTO);
                asset.setUploadedAt(i % 2 == 0 ? null : LocalDateTime.of(2026, 10, 17, 1, i)); asset.setOriginalFileName("photo.jpg"); asset.setContentType("image/jpeg"); asset.setFileSize(12L); asset.setGpsLatitude(new BigDecimal("36.1")); asset.setGpsLongitude(new BigDecimal("126.1")); asset.setRepresentativeOverride(null); pictures.add(asset);
                var path = new TravelRouteSegment(); path.setId(40L + i); path.setPlan(p); path.setRouteDate(i % 2 == 0 ? null : p.getStartDate()); path.setTitle("Path"); path.setSourceType(i % 2 == 0 ? TravelRouteSourceType.GPX : TravelRouteSourceType.MANUAL);
                path.setDistanceKm(i == 3 ? null : new BigDecimal("1.23456")); path.setDurationMinutes(i % 2 == 0 ? null : 10); path.setStepCount(i % 2 == 0 ? null : 20); path.setLineStyle(null); path.setLineColorHex(null); path.setGpxFilesJson("files"); path.setRoutePathJson("snapshot"); paths.add(path);
            }
            when(budgets.findAllByPlanOwnerId(7L)).thenReturn(b); when(records.findAllByPlanOwnerId(7L)).thenReturn(r);
            when(media.findAllByPlanOwnerIdOrderByUploadedAtDescIdDesc(7L)).thenReturn(pictures); when(routes.findAllByPlanOwnerIdOrderByRouteDateDescIdDesc(7L)).thenReturn(paths);
            for (var p : heads) {
                when(budgets.findAllByPlanIdAndPlanOwnerIdOrderByDisplayOrderAscIdAsc(p.getId(), 7L)).thenReturn(b.stream().filter(v -> v.getPlan() == p).toList());
                when(records.findAllByPlanIdAndPlanOwnerIdOrderByExpenseDateDescIdDesc(p.getId(), 7L)).thenReturn(r.stream().filter(v -> v.getPlan() == p).toList());
                when(media.findAllByPlanIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(p.getId(), 7L)).thenReturn(pictures.stream().filter(v -> v.getPlan() == p).toList());
                when(routes.findAllByPlanIdAndPlanOwnerIdOrderByRouteDateDescIdDesc(p.getId(), 7L)).thenReturn(paths.stream().filter(v -> v.getPlan() == p).toList());
            }
            var aggregate = mock(TravelBudgetItemRepository.PlanBudgetAggregate.class); when(aggregate.getPlanId()).thenReturn(1L); when(aggregate.getTotalAmountKrw()).thenReturn(new BigDecimal("10.005")); when(aggregate.getItemCount()).thenReturn((long) Integer.MAX_VALUE + seed);
            when(budgets.summarizeByPlanOwnerId(7L)).thenReturn(List.of(aggregate));
            when(payload.files("files")).thenReturn(Arrays.asList(new GpxFile(null, "ignored", "xml", 1), new GpxFile("original.gpx", "test-only/gpx", "xml", 1)));
            when(payload.resolve(any(), eq("files"), eq("snapshot"))).thenReturn(List.of(new Point(new BigDecimal("37.1"), new BigDecimal("127.1"), "MEMORY", 21L, "Full original")));
            old = new OwnedTravelPlansLegacyOracle(users, plans, budgets, records, media, routes, redis, new TravelSummaryCache(redis), payload);
            modern = new ReadOwnedTravelPlansService(new JpaOwnedTravelPlansAdapter(users, plans, budgets, records, media, routes, payload), new OwnedTravelPlansCache(redis, new TravelSummaryCache(redis), 0), payload);
        }
    }
}
