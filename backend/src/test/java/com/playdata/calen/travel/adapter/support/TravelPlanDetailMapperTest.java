package com.playdata.calen.travel.adapter.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.travel.application.port.out.TravelRoutePayloadPort;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TravelPlanDetailMapperTest {
    @Test void detachedProjectionKeepsRawFieldsNullsRoundingOrderAndFullGpxBehavior() throws Exception {
        var plan = new TravelPlan(); plan.setId(3L); plan.setName("Trip"); plan.setDestination("Place"); plan.setStatus(null);
        plan.setColorHex("#abcdef"); plan.setHomeCurrency("usd"); plan.setHeadCount(2); plan.setMemo(" raw memo ");
        plan.setStartDate(LocalDate.of(2026, 10, 16)); plan.setEndDate(LocalDate.of(2026, 10, 17));
        plan.setPublicShared(true); plan.setPublicSharedAt(LocalDateTime.of(2026, 10, 16, 12, 0));
        var first = budget(8L, null, "10.005"); var second = budget(7L, 1, "20.004");
        var ledger = record(plan, 11L, null, "10.005"); var expense = record(plan, 12L, TravelRecordType.LEDGER, "1.004");
        var memory = record(plan, 21L, TravelRecordType.MEMORY, "999"); memory.setSharedWithCommunity(null);
        var author = new AppUser(); author.setDisplayName("Author");
        var asset = new TravelMediaAsset(); asset.setId(4L); asset.setPlan(plan); asset.setRecord(memory); asset.setUploadedBy(author);
        asset.setMediaType(TravelMediaType.PHOTO); asset.setOriginalFileName("photo.jpg"); asset.setContentType("image/jpeg"); asset.setFileSize(12L);
        asset.setUploadedAt(LocalDateTime.of(2026, 10, 16, 13, 0)); asset.setRepresentativeOverride(null);
        var route = new TravelRouteSegment(); route.setId(31L); route.setPlan(plan); route.setRouteDate(plan.getStartDate()); route.setTitle("Route");
        route.setTransportMode(TravelRouteTransportMode.WALK); route.setSourceType(TravelRouteSourceType.GPX);
        route.setDistanceKm(new BigDecimal("1.23456")); route.setDurationMinutes(null); route.setStepCount(null);
        route.setRoutePathJson("snapshot"); route.setGpxFilesJson("files"); route.setLineColorHex(null); route.setLineStyle(null);
        var payload = mock(TravelRoutePayloadPort.class);
        when(payload.files("files")).thenReturn(Arrays.asList(new GpxFile(null, "ignored", "xml", 1), new GpxFile("original.gpx", "test-only/gpx", "xml", 1)));
        when(payload.resolve(TravelRouteSourceType.GPX, "files", "snapshot")).thenReturn(List.of(new Point(new BigDecimal("37.1"), new BigDecimal("127.1"))));
        var expected = TravelPlanDetailLegacyOracle.expected(plan, List.of(second, first), List.of(ledger, expense), List.of(memory), List.of(asset), List.of(route),
                payload, id -> "/synthetic-scope/" + id);
        clearInvocations(payload);
        var detail = TravelPlanDetailMapper.snapshot(plan, List.of(second, first), List.of(ledger, expense), List.of(memory), List.of(asset), List.of(route), payload);

        // Mutation after projection must not change the detached value or its later HTTP representation.
        plan.setName("changed"); memory.setTitle("changed"); asset.setOriginalFileName("changed.jpg");
        var result = TravelPlanDetailMapper.response(detail, id -> "/synthetic-scope/" + id);
        assertThat(result.name()).isEqualTo("Trip"); assertThat(result.status()).isEqualTo("PLANNED"); assertThat(result.colorHex()).isEqualTo("#ABCDEF");
        assertThat(result.homeCurrency()).isEqualTo("usd"); assertThat(result.memo()).isEqualTo(" raw memo ");
        assertThat(result.plannedTotalKrw()).isEqualTo(new BigDecimal("30.01")); assertThat(result.actualTotalKrw()).isEqualTo(new BigDecimal("11.01"));
        assertThat(result.budgetItems()).extracting(value -> value.id()).containsExactly(8L, 7L);
        assertThat(result.budgetItems().get(0).amountKrw()).isEqualTo(new BigDecimal("10.005"));
        assertThat(result.budgetItems().get(0).displayOrder()).isNull();
        assertThat(result.records()).extracting(value -> value.id()).containsExactly(12L, 11L);
        assertThat(result.records().get(1).recordType()).isNull(); assertThat(result.records().get(1).amountKrw()).isEqualTo(new BigDecimal("10.005"));
        assertThat(result.memoryRecords().get(0).title()).isEqualTo("Record"); assertThat(result.memoryRecords().get(0).sharedWithCommunity()).isFalse();
        assertThat(result.mediaItems().get(0).contentUrl()).isEqualTo("/synthetic-scope/4"); assertThat(result.mediaItems().get(0).originalFileName()).isEqualTo("photo.jpg");
        assertThat(result.mediaItems().get(0).representativeOverride()).isFalse();
        assertThat(result.totalDistanceKm()).isEqualTo(new BigDecimal("1.235")); assertThat(result.totalDurationMinutes()).isZero(); assertThat(result.totalStepCount()).isZero();
        assertThat(result.routeSegments().get(0).durationMinutes()).isZero(); assertThat(result.routeSegments().get(0).stepCount()).isNull();
        assertThat(result.routeSegments().get(0).lineColorHex()).isEqualTo("#ABCDEF"); assertThat(result.routeSegments().get(0).lineStyle()).isEqualTo(TravelRouteLineStyle.SOLID);
        assertThat(result.routeSegments().get(0).gpxFileNames()).containsExactly("original.gpx");
        verify(payload).resolve(TravelRouteSourceType.GPX, "files", "snapshot"); verify(payload, never()).decode(any());
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        assertThat(mapper.writeValueAsString(result)).isEqualTo(mapper.writeValueAsString(expected));
        var json = mapper.valueToTree(result);
        assertThat(json.size()).isEqualTo(27); assertThat(json.get("publicShared").asBoolean()).isTrue();
        assertThat(result.budgetItemCount()).isEqualTo(2); assertThat(result.recordCount()).isEqualTo(2); assertThat(result.memoryRecordCount()).isEqualTo(1);
        assertThat(result.mediaItemCount()).isEqualTo(1); assertThat(result.routeSegmentCount()).isEqualTo(1);
    }

    @Test void completeJsonMatchesFrozenLegacyAssemblyAcrossGeneratedDetailShapes() throws Exception {
        var random = new Random(20261016L);
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var payload = mock(TravelRoutePayloadPort.class);
        when(payload.files(any())).thenReturn(List.of());
        when(payload.resolve(any(), any(), any())).thenReturn(List.of(new Point(new BigDecimal("37.1"), new BigDecimal("127.1"))));
        for (int sample = 0; sample < 150; sample++) {
            var plan = new TravelPlan(); plan.setId(3L); plan.setName("Trip " + sample); plan.setDestination(sample % 2 == 0 ? null : "Place");
            plan.setColorHex(sample % 3 == 0 ? null : "#abcdef"); plan.setStatus(sample % 4 == 0 ? null : TravelPlanStatus.values()[sample % 3]);
            plan.setHomeCurrency(sample % 2 == 0 ? "usd" : "KRW"); plan.setHeadCount(sample % 3 == 0 ? null : 2); plan.setMemo(sample % 2 == 0 ? null : " raw ");
            plan.setStartDate(LocalDate.of(2026, 10, 16)); plan.setEndDate(sample % 3 == 0 ? null : LocalDate.of(2026, 10, 17));
            plan.setPublicShared(sample % 3 == 0 ? null : sample % 2 == 0); plan.setPublicSharedAt(sample % 2 == 0 ? null : LocalDateTime.of(2026, 10, 16, 12, 34));
            var budgets = new ArrayList<TravelBudgetItem>(); var records = new ArrayList<TravelExpenseRecord>(); var memories = new ArrayList<TravelExpenseRecord>();
            var media = new ArrayList<TravelMediaAsset>(); var routes = new ArrayList<TravelRouteSegment>();
            for (int index = 0; index < random.nextInt(5); index++) {
                var item = budget(8L + index, index % 2 == 0 ? null : random.nextInt(3), String.valueOf(random.nextInt(10000) - 5000) + ".005");
                if (sample % 5 == 0) item.setAmountKrw(null);
                budgets.add(item);
            }
            for (int index = 0; index < random.nextInt(5); index++) {
                var item = record(plan, 11L + index, index % 2 == 0 ? null : TravelRecordType.LEDGER, "12.005");
                if (index % 2 == 0) item.setExpenseTime(null);
                records.add(item);
            }
            for (int index = 0; index < random.nextInt(4); index++) {
                var item = record(plan, 21L + index, TravelRecordType.MEMORY, "0");
                item.setSharedWithCommunity(index % 2 == 0 ? null : true); if (index % 2 == 0) item.setExpenseDate(null);
                memories.add(item);
            }
            for (int index = 0; index < random.nextInt(4); index++) {
                var author = new AppUser(); author.setDisplayName(sample % 2 == 0 ? null : "Author");
                var asset = new TravelMediaAsset(); asset.setId(41L + index); asset.setPlan(plan); asset.setRecord(record(plan, 21L, TravelRecordType.MEMORY, "0"));
                asset.setUploadedBy(author); asset.setOriginalFileName("photo" + index + ".jpg"); asset.setContentType("image/jpeg"); asset.setFileSize(12L);
                asset.setUploadedAt(index % 2 == 0 ? null : LocalDateTime.of(2026, 10, 16, 12, 34));
                asset.setRepresentativeOverride(index % 2 == 0 ? null : true); media.add(asset);
            }
            for (int index = 0; index < random.nextInt(4); index++) {
                var route = new TravelRouteSegment(); route.setId(31L + index); route.setPlan(plan); route.setRouteDate(index % 2 == 0 ? null : plan.getStartDate());
                route.setTitle("Route"); route.setTransportMode(TravelRouteTransportMode.WALK); route.setSourceType(TravelRouteSourceType.MANUAL);
                route.setDistanceKm(index % 2 == 0 ? null : new BigDecimal("1.23456")); route.setDurationMinutes(index % 2 == 0 ? null : 5);
                route.setStepCount(index % 2 == 0 ? null : 10); route.setLineColorHex(null); route.setLineStyle(null); routes.add(route);
            }
            Collections.shuffle(budgets, random); Collections.shuffle(records, random); Collections.shuffle(memories, random);
            Collections.shuffle(media, random); Collections.shuffle(routes, random);
            var expected = TravelPlanDetailLegacyOracle.expected(plan, budgets, records, memories, media, routes, payload, id -> "/scope/" + id);
            var actual = TravelPlanDetailMapper.response(TravelPlanDetailMapper.snapshot(plan, budgets, records, memories, media, routes, payload), id -> "/scope/" + id);
            assertThat(mapper.writeValueAsString(actual)).as("complete detail JSON sample %s", sample).isEqualTo(mapper.writeValueAsString(expected));
        }
    }

    @Test void listComparatorsKeepThePreviousNullAndTieRules() {
        var plan = new TravelPlan(); var older = record(plan, 1L, null, "0"); var newer = record(plan, 2L, null, "0");
        var undated = record(plan, 3L, null, "0"); undated.setExpenseDate(null);
        var untimed = record(plan, 4L, null, "0"); untimed.setExpenseTime(null);
        assertThat(List.of(older, undated, newer, untimed).stream().sorted(TravelPlanDetailMapper.RECORD_ORDER).toList())
                .extracting(TravelExpenseRecord::getId).containsExactly(2L, 1L, 4L, 3L);
    }

    TravelBudgetItem budget(long id, Integer order, String amount) {
        var item = new TravelBudgetItem(); item.setId(id); item.setCategory("Category"); item.setTitle("Budget");
        item.setAmount(new BigDecimal("0.123456")); item.setAmountKrw(new BigDecimal(amount)); item.setCurrencyCode("usd");
        item.setExchangeRateToKrw(new BigDecimal("1.123456")); item.setMemo(null); item.setDisplayOrder(order); return item;
    }
    TravelExpenseRecord record(TravelPlan plan, long id, TravelRecordType type, String amount) {
        var record = new TravelExpenseRecord(); record.setId(id); record.setPlan(plan); record.setRecordType(type); record.setTitle("Record");
        record.setCategory("Category"); record.setExpenseDate(LocalDate.of(2026, 10, 16)); record.setExpenseTime(LocalTime.of(12, 34));
        record.setAmount(new BigDecimal("0.123456")); record.setAmountKrw(new BigDecimal(amount)); record.setCurrencyCode("usd");
        record.setLatitude(new BigDecimal("37.123456789")); record.setLongitude(new BigDecimal("127.123456789")); return record;
    }
}
