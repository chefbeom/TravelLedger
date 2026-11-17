package com.playdata.calen.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.repository.*;
import com.playdata.calen.ledger.service.LedgerAdjustmentService;
import com.playdata.calen.ledger.dto.LedgerAdjustmentRequest;
import com.playdata.calen.recordmap.domain.*;
import com.playdata.calen.recordmap.repository.*;
import com.playdata.calen.recordmap.service.RecordMapShareService;
import com.playdata.calen.recordmap.dto.RecordMapDtos.ShareRequest;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.sharing.domain.*;
import com.playdata.calen.sharing.repository.RecordShareRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;

@SpringBootTest(properties = { "app.seed.enabled=false", "spring.datasource.url=jdbc:h2:mem:integrated-calendar;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=" })
@AutoConfigureMockMvc
class CalendarEventIntegrationTest {
    static final String PATH = "/api/integrated-calendar/events";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AppUserRepository users;
    @Autowired CategoryGroupRepository categories;
    @Autowired PaymentMethodRepository payments;
    @Autowired LedgerEntryRepository ledger;
    @Autowired LedgerAdjustmentService adjustments;
    @Autowired RecordPlaceRepository places;
    @Autowired PlaceVisitRepository visits;
    @Autowired PlacePhotoRepository placePhotos;
    @Autowired PlaceCollectionRepository placeCollections;
    @Autowired RecordMapShareService mapShares;
    @Autowired RecordMapShareRepository mapShareRows;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired TravelRouteSegmentRepository routes;
    @Autowired TravelPlanShareRepository planShares;
    @Autowired TravelShareGroupRepository groups;
    @Autowired TravelShareGroupMemberRepository members;
    @Autowired RecordShareRepository recordShares;
    AppUser first, second;

    @BeforeEach void setup() { first = createUser(); second = createUser(); }
    private AppUser createUser() {
        var user = new AppUser(); user.setLoginId("calendar-" + UUID.randomUUID()); user.setDisplayName("일정 테스트"); user.setPasswordHash("test");
        return users.saveAndFlush(user);
    }
    private ObjectNode draft() throws Exception {
        return (ObjectNode) mapper.readTree("{\"title\":\"여행 준비\",\"startDate\":\"2024-02-28\",\"endDate\":\"2024-03-02\",\"allDay\":true,\"memo\":\"메모\"}");
    }
    private JsonNode create(AppUser owner, ObjectNode draft) throws Exception {
        var response = mvc.perform(post(PATH).with(user(AppUserPrincipal.from(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(draft)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
    private JsonNode list(AppUser owner, String from, String to) throws Exception {
        return mapper.readTree(mvc.perform(get(PATH).with(user(AppUserPrincipal.from(owner))).param("from", from).param("to", to))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test void privateEventsSupportCrossMonthEditingAndDeletionWithVersionChecks() throws Exception {
        var saved = create(first, draft()); long id = saved.path("id").asLong();
        assertThat(list(first, "2024-03-01", "2024-03-31")).hasSize(1);
        JsonNode monthEvent = summary(first, "/api/integrated-calendar/summary/events").get(0);
        assertThat(monthEvent.path("title").asText()).isEqualTo("여행 준비");
        assertThat(monthEvent.has("memo")).isFalse();
        assertThat(monthEvent.has("startTime")).isFalse();
        assertThat(list(second, "2024-03-01", "2024-03-31")).isEmpty();
        ObjectNode update = draft().put("title", "수정된 일정").put("version", saved.path("version").asLong());
        mvc.perform(put(PATH + "/" + id).with(user(AppUserPrincipal.from(second))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(update))).andExpect(status().isNotFound());
        JsonNode edited = mapper.readTree(mvc.perform(put(PATH + "/" + id).with(user(AppUserPrincipal.from(first))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(update)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(put(PATH + "/" + id).with(user(AppUserPrincipal.from(first))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(update))).andExpect(status().isConflict());
        mvc.perform(delete(PATH + "/" + id).with(user(AppUserPrincipal.from(second))).with(csrf()).param("version", edited.path("version").asText())).andExpect(status().isNotFound());
        mvc.perform(delete(PATH + "/" + id).with(user(AppUserPrincipal.from(first))).with(csrf()).param("version", edited.path("version").asText())).andExpect(status().isNoContent());
        assertThat(list(first, "2024-02-01", "2024-03-31")).isEmpty();
    }
    @Test void invalidDatesAndTimesAreRejectedBeforeSaving() throws Exception {
        ObjectNode invalid = draft().put("endDate", "2024-02-01");
        mvc.perform(post(PATH).with(user(AppUserPrincipal.from(first))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(invalid))).andExpect(status().isBadRequest());
        invalid = draft().put("startTime", "09:00");
        mvc.perform(post(PATH).with(user(AppUserPrincipal.from(first))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(invalid))).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("from", "2024-01-01").param("to", "2024-01-31")).andExpect(status().isUnauthorized());
        assertThat(list(first, "2024-01-01", "2024-12-31")).isEmpty();
    }
    @Test void separateSessionsForSameAccountSeeTheSameSavedEvent() throws Exception {
        var firstDevice = new MockHttpSession(); var secondDevice = new MockHttpSession();
        JsonNode created = mapper.readTree(mvc.perform(post(PATH).session(firstDevice).with(user(AppUserPrincipal.from(first))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(draft())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode visible = mapper.readTree(mvc.perform(get(PATH).session(secondDevice).with(user(AppUserPrincipal.from(first)))
                .param("from", "2024-02-29").param("to", "2024-02-29"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).path("id").asLong()).isEqualTo(created.path("id").asLong());
        ObjectNode changed = draft().put("title", "다른 기기에서 변경").put("version", created.path("version").asLong());
        mvc.perform(put(PATH + "/" + created.path("id").asLong()).session(secondDevice).with(user(AppUserPrincipal.from(first))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(changed))).andExpect(status().isOk());
        JsonNode again = mapper.readTree(mvc.perform(get(PATH).session(firstDevice).with(user(AppUserPrincipal.from(first)))
                .param("from", "2024-02-29").param("to", "2024-02-29"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(again.get(0).path("title").asText()).isEqualTo("다른 기기에서 변경");
    }
    @Test void actualRefundAndCancellationUseTheirOwnDatesAndLedgerAmounts() throws Exception {
        var expense = new CategoryGroup(); expense.setOwner(first); expense.setName("지출"); expense.setEntryType(EntryType.EXPENSE); categories.saveAndFlush(expense);
        var income = new CategoryGroup(); income.setOwner(first); income.setName("환불 수입"); income.setEntryType(EntryType.INCOME); categories.saveAndFlush(income);
        var payment = new PaymentMethod(); payment.setOwner(first); payment.setName("현금"); payment.setKind(PaymentMethodKind.CASH); payments.saveAndFlush(payment);
        var purchase = new LedgerEntry(); purchase.setOwner(first); purchase.setEntryDate(LocalDate.of(2024, 1, 15)); purchase.setTitle("카드 구매"); purchase.setAmount(new BigDecimal("30000")); purchase.setEntryType(EntryType.EXPENSE); purchase.setCategoryGroup(expense); purchase.setPaymentMethod(payment); ledger.saveAndFlush(purchase);
        var cancelled = new LedgerEntry(); cancelled.setOwner(first); cancelled.setEntryDate(LocalDate.of(2024, 1, 15)); cancelled.setTitle("취소 구매"); cancelled.setAmount(new BigDecimal("7000")); cancelled.setEntryType(EntryType.EXPENSE); cancelled.setCategoryGroup(expense); cancelled.setPaymentMethod(payment); ledger.saveAndFlush(cancelled);
        LocalDate settlement = LocalDate.of(2024, 2, 29);
        adjustments.create(first.getId(), purchase.getId(), new LedgerAdjustmentRequest(LedgerAdjustmentType.REFUND, settlement, null, new BigDecimal("30000"), new BigDecimal("5000"), BigDecimal.ZERO, income.getId(), payment.getId(), null, UUID.randomUUID()));
        adjustments.create(first.getId(), cancelled.getId(), new LedgerAdjustmentRequest(LedgerAdjustmentType.PAYMENT_CANCELLATION, settlement, null, null, null, null, null, null, null, UUID.randomUUID()));
        JsonNode january = mapper.readTree(mvc.perform(get("/api/integrated-calendar/summary/ledger").with(user(AppUserPrincipal.from(first))).param("from", "2024-01-01").param("to", "2024-01-31"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(january.get(0).path("expense").decimalValue()).isEqualByComparingTo("30000");
        JsonNode february = summary(first, "/api/integrated-calendar/summary/ledger");
        assertThat(february.get(0).path("income").decimalValue()).isEqualByComparingTo("25000");
        assertThat(february.get(0).path("expense").decimalValue()).isEqualByComparingTo("0");
        JsonNode actual = day(first, "ledger");
        assertThat(actual.findValuesAsText("adjustmentType")).contains("REFUND", "PAYMENT_CANCELLATION");
        JsonNode refund = null; for (JsonNode item : actual) if ("REFUND".equals(item.path("adjustmentType").asText())) refund = item;
        assertThat(refund).isNotNull(); assertThat(refund.path("adjustmentOriginId").asLong()).isEqualTo(purchase.getId());
        assertThat(refund.path("adjustmentPrincipal").decimalValue()).isEqualByComparingTo("30000");
        assertThat(refund.path("adjustmentDeduction").decimalValue()).isEqualByComparingTo("5000");
        assertThat(refund.path("amount").decimalValue()).isEqualByComparingTo("25000");
    }
    @Test void summariesRespectOwnershipAndDoNotTruncateVisitsOrInventTripPeriods() throws Exception {
        var category = new CategoryGroup(); category.setOwner(first); category.setName("식비"); category.setEntryType(EntryType.EXPENSE); categories.saveAndFlush(category);
        var payment = new PaymentMethod(); payment.setOwner(first); payment.setName("카드"); payment.setKind(PaymentMethodKind.CARD); payments.saveAndFlush(payment);
        for (int i = 0; i < 3; i++) {
            var entry = new LedgerEntry(); entry.setOwner(first); entry.setEntryDate(LocalDate.of(2024, 2, 29)); entry.setTitle("거래"); entry.setEntryType(EntryType.EXPENSE);
            entry.setCategoryGroup(category); entry.setPaymentMethod(payment); entry.setAmount(BigDecimal.valueOf(i == 0 ? 30000 : 10000));
            if (i < 2) entry.setSplitPaymentGroupId("test-group"); else entry.setFinanciallyExcluded(true);
            ledger.save(entry);
        }
        ledger.flush();
        var place = new RecordPlace(); place.setOwnerId(first.getId()); place.setName("장소"); place.setLatitude(37.0); place.setLongitude(127.0); places.saveAndFlush(place);
        for (int i = 0; i < 125; i++) { var visit = new PlaceVisit(); visit.setOwnerId(first.getId()); visit.setPlace(place); visit.setTitle("방문"); visit.setVisitDate(LocalDate.of(2024, 2, 29)); visits.save(visit); }
        var undated = new PlaceVisit(); undated.setOwnerId(first.getId()); undated.setPlace(place); undated.setTitle("날짜 없음"); visits.saveAndFlush(undated);
        var noPeriod = new TravelPlan(); noPeriod.setOwner(first); noPeriod.setName("기간 없음"); plans.saveAndFlush(noPeriod);
        var route = new TravelRouteSegment(); route.setPlan(noPeriod); route.setTitle("경로"); route.setRouteDate(LocalDate.of(2024, 2, 29)); route.setRoutePathJson("[]"); routes.saveAndFlush(route);
        String base = "/api/integrated-calendar/summary/";
        JsonNode money = summary(first, base + "ledger");
        assertThat(money).hasSize(1); assertThat(money.get(0).path("expense").decimalValue()).isEqualByComparingTo("40000");
        assertThat(money.get(0).path("rowCount").asLong()).isEqualTo(3); assertThat(money.get(0).path("transactionCount").asLong()).isEqualTo(2);
        JsonNode categorySummary = summary(first, base + "ledger-categories");
        assertThat(categorySummary.get(0).path("name").asText()).isEqualTo("식비");
        assertThat(categorySummary.get(0).path("amount").decimalValue()).isEqualByComparingTo("40000");
        assertThat(summary(second, base + "ledger-categories")).isEmpty();
        assertThat(summary(first, base + "places").get(0).path("count").asLong()).isEqualTo(125);
        JsonNode travel = summary(first, base + "travel"); assertThat(travel.path("periods")).isEmpty(); assertThat(travel.at("/routes/0/count").asLong()).isEqualTo(1);
        assertThat(summary(second, base + "ledger")).isEmpty(); assertThat(summary(second, base + "places")).isEmpty();
        assertThat(summary(second, base + "travel").path("routes")).isEmpty();
        assertThat(summary(first, base + "events")).isEmpty();
        assertThat(day(first, "places")).hasSize(125);
        assertThat(day(first, "ledger")).hasSize(3);
        assertThat(day(first, "routes")).hasSize(1);
        assertThat(day(second, "places")).isEmpty();
        assertThat(day(second, "ledger")).isEmpty();
        assertThat(day(second, "routes")).isEmpty();
        JsonNode undatedResult = mapper.readTree(mvc.perform(get("/api/integrated-calendar/undated").with(user(AppUserPrincipal.from(first))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(undatedResult.at("/places/total").asLong()).isEqualTo(1);
        assertThat(undatedResult.at("/trips/total").asLong()).isEqualTo(1);
        JsonNode otherUndated = mapper.readTree(mvc.perform(get("/api/integrated-calendar/undated").with(user(AppUserPrincipal.from(second))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(otherUndated.at("/places/total").asLong()).isZero();
        assertThat(otherUndated.at("/trips/total").asLong()).isZero();
    }
    @Test void sharedTripRequiresSelectionAndCurrentPermissionOnEveryRequest() throws Exception {
        var plan = new TravelPlan(); plan.setOwner(first); plan.setName("공유 여행"); plan.setStatus(TravelPlanStatus.COMPLETED);
        plan.setStartDate(LocalDate.of(2024, 2, 28)); plan.setEndDate(LocalDate.of(2024, 3, 2)); plans.saveAndFlush(plan);
        var share = new TravelPlanShare(); share.setPlan(plan); share.setSharedBy(first); share.setRecipient(second); share.setCreatedAt(LocalDateTime.now()); planShares.saveAndFlush(share);
        String base = "/api/integrated-calendar/shared";
        String path = base + "/summary/exhibit/" + share.getId();
        JsonNode options = mapper.readTree(mvc.perform(get(base + "/options/exhibit").with(user(AppUserPrincipal.from(second))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(options.at("/items/0/selected").asBoolean()).isFalse();
        mvc.perform(get(path).with(user(AppUserPrincipal.from(second))).param("from", "2024-02-01").param("to", "2024-03-09")).andExpect(status().isNotFound());
        mvc.perform(put(base + "/selections/exhibit/" + share.getId()).with(user(AppUserPrincipal.from(second))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"selected\":true}")).andExpect(status().isNoContent());
        options = mapper.readTree(mvc.perform(get(base + "/options/exhibit").with(user(AppUserPrincipal.from(second))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(options.at("/items/0/selected").asBoolean()).isTrue();
        JsonNode received = mapper.readTree(mvc.perform(get(path).with(user(AppUserPrincipal.from(second))).param("from", "2024-02-01").param("to", "2024-03-09"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(received.path("title").asText()).isEqualTo("공유 여행");
        assertThat(received.at("/data/periods/0/startDate").asText()).isEqualTo("2024-02-28");
        mvc.perform(get(base + "/day/exhibit/" + share.getId()).with(user(AppUserPrincipal.from(second))).param("date", "2024-02-29")).andExpect(status().isOk());
        mvc.perform(get(path).with(user(AppUserPrincipal.from(first))).param("from", "2024-02-01").param("to", "2024-03-09")).andExpect(status().isNotFound());
        planShares.deleteById(share.getId()); planShares.flush();
        mvc.perform(get(path).with(user(AppUserPrincipal.from(second))).param("from", "2024-02-01").param("to", "2024-03-09")).andExpect(status().isNotFound());
        mvc.perform(get(base + "/day/exhibit/" + share.getId()).with(user(AppUserPrincipal.from(second))).param("date", "2024-02-29")).andExpect(status().isNotFound());
        mvc.perform(put(base + "/selections/exhibit/" + share.getId()).with(user(AppUserPrincipal.from(second))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"selected\":false}")).andExpect(status().isNoContent());
    }
    @Test void importedTravelPlaceUsesOnlyItsActualSourceDateAndKeepsIndependentVisits() throws Exception {
        var plan = new TravelPlan(); plan.setOwner(first); plan.setName("기간 없는 여행"); plans.saveAndFlush(plan);
        var record = new TravelExpenseRecord(); record.setPlan(plan); record.setRecordType(TravelRecordType.MEMORY);
        record.setExpenseDate(LocalDate.of(2024, 2, 29)); record.setTitle("원본 기억"); record.setCategory("장소"); record.setAmount(BigDecimal.ZERO); records.saveAndFlush(record);
        var place = new RecordPlace(); place.setOwnerId(first.getId()); place.setName("가져온 장소"); place.setLatitude(37.0); place.setLongitude(127.0);
        place.setSourceTravelRecordId(record.getId()); places.saveAndFlush(place);
        var visit = new PlaceVisit(); visit.setOwnerId(first.getId()); visit.setPlace(place); visit.setTitle("별도 방문"); visit.setVisitDate(LocalDate.of(2024, 2, 29)); visits.saveAndFlush(visit);
        JsonNode summary = summary(first, "/api/integrated-calendar/summary/places");
        assertThat(summary.get(0).path("count").asLong()).isEqualTo(2);
        assertThat(summary(first, "/api/integrated-calendar/summary/travel").at("/records/0/importedCount").asLong()).isEqualTo(1);
        JsonNode actual = day(first, "places"); assertThat(actual).hasSize(2);
        assertThat(actual.findValuesAsText("sourceKind")).containsExactlyInAnyOrder("VISIT", "TRAVEL_IMPORT");
        assertThat(actual.findValuesAsText("sourceTravelRecordId")).containsOnly(record.getId().toString());
        JsonNode filteredPlaces = mapper.readTree(mvc.perform(get("/api/integrated-calendar/day/places").with(user(AppUserPrincipal.from(first))).param("date", "2024-02-29").param("planId", plan.getId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(filteredPlaces).hasSize(1);
        assertThat(filteredPlaces.get(0).path("sourceKind").asText()).isEqualTo("TRAVEL_IMPORT");
        JsonNode filteredSummary = mapper.readTree(mvc.perform(get("/api/integrated-calendar/summary/places").with(user(AppUserPrincipal.from(first))).param("from", "2024-02-01").param("to", "2024-03-09").param("planId", plan.getId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(filteredSummary.get(0).path("count").asLong()).isEqualTo(1);
        assertThat(day(first, "travel-records")).hasSize(1);
        String sourcePath = "/api/integrated-calendar/day/place-source/" + place.getId();
        JsonNode source = mapper.readTree(mvc.perform(get(sourcePath).with(user(AppUserPrincipal.from(first))).param("visitId", visit.getId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(source.at("/place/id").asLong()).isEqualTo(place.getId());
        assertThat(source.at("/visit/id").asText()).isEqualTo("visit:" + visit.getId());
        mvc.perform(get(sourcePath).with(user(AppUserPrincipal.from(second))).param("visitId", visit.getId().toString())).andExpect(status().isNotFound());
        mvc.perform(get(sourcePath).with(user(AppUserPrincipal.from(first))).param("visitId", "99999999")).andExpect(status().isNotFound());
        assertThat(day(second, "places")).isEmpty();
        assertThat(day(second, "travel-records")).isEmpty();
    }
    @Test void orphanedImportedPlaceIsListedAsUndatedRatherThanInventingDate() throws Exception {
        var place = new RecordPlace(); place.setOwnerId(first.getId()); place.setName("원본이 없는 장소"); place.setLatitude(37.0); place.setLongitude(127.0);
        place.setSourceTravelRecordId(999999999L); places.saveAndFlush(place);
        JsonNode undated = mapper.readTree(mvc.perform(get("/api/integrated-calendar/undated").with(user(AppUserPrincipal.from(first))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(undated.at("/places/total").asLong()).isEqualTo(1);
        assertThat(undated.at("/places/items/0/id").asLong()).isEqualTo(place.getId());
        assertThat(summary(first, "/api/integrated-calendar/summary/places")).isEmpty();
    }
    @Test void legacyNullTravelTypeIsClassifiedAsLedgerAndCombinedWithLedgerSummary() throws Exception {
        var plan = new TravelPlan(); plan.setOwner(first); plan.setName("예전 여행"); plans.saveAndFlush(plan);
        for (int i = 0; i < 2; i++) {
            var record = new TravelExpenseRecord(); record.setPlan(plan); record.setRecordType(i == 0 ? null : TravelRecordType.LEDGER);
            record.setExpenseDate(LocalDate.of(2024, 2, 29)); record.setTitle("여행 지출"); record.setCategory("식비"); record.setAmount(new BigDecimal("5000")); record.setAmountKrw(new BigDecimal("5000")); records.save(record);
        }
        records.flush();
        JsonNode summary = summary(first, "/api/integrated-calendar/summary/travel");
        assertThat(summary.path("records")).hasSize(1);
        assertThat(summary.at("/records/0/type").asText()).isEqualTo("LEDGER");
        assertThat(summary.at("/records/0/count").asLong()).isEqualTo(2);
        assertThat(day(first, "travel-records").findValuesAsText("type")).containsOnly("LEDGER");
    }
    @Test void sharedMapRequiresLinkPossessionAndRechecksRevocationAndVisitScope() throws Exception {
        var collection = new PlaceCollection(); collection.setOwnerId(first.getId()); collection.setName("공유 모음"); placeCollections.saveAndFlush(collection);
        var place = new RecordPlace(); place.setOwnerId(first.getId()); place.setName("공유 장소"); place.setLatitude(37.0); place.setLongitude(127.0); place.getCollections().add(collection); places.saveAndFlush(place);
        var visit = new PlaceVisit(); visit.setOwnerId(first.getId()); visit.setPlace(place); visit.setTitle("방문 기록"); visit.setVisitDate(LocalDate.of(2024, 2, 29)); visits.saveAndFlush(visit);
        var photo = new PlacePhoto(); photo.setOwnerId(first.getId()); photo.setVisit(visit); photo.setOriginalName("test.png"); photo.setStoragePath("missing-test.png"); photo.setContentType("image/png"); photo.setFileSize(1); placePhotos.saveAndFlush(photo);
        var share = mapShares.create(first.getId(), collection.getId(), new ShareRequest(true, 7));
        String base = "/api/integrated-calendar/shared-map";
        mvc.perform(post(base + "/links").with(user(AppUserPrincipal.from(second))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(mapper.createObjectNode().put("token", share.token())))).andExpect(status().isNoContent());
        JsonNode options = mapper.readTree(mvc.perform(get(base + "/options").with(user(AppUserPrincipal.from(second))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long id = options.at("/items/0/id").asLong();
        assertThat(options.toString()).doesNotContain(share.token());
        assertThat(options.at("/items/0/selected").asBoolean()).isTrue();
        JsonNode otherDevice = mapper.readTree(mvc.perform(get(base + "/options").session(new MockHttpSession()).with(user(AppUserPrincipal.from(second))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(otherDevice.at("/items/0/id").asLong()).isEqualTo(id);
        assertThat(otherDevice.at("/items/0/selected").asBoolean()).isTrue();
        JsonNode month = mapper.readTree(mvc.perform(get(base + "/" + id + "/summary").with(user(AppUserPrincipal.from(second))).param("from", "2024-02-01").param("to", "2024-02-29"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(month.at("/days/0/count").asLong()).isEqualTo(1);
        JsonNode day = mapper.readTree(mvc.perform(get(base + "/" + id + "/day").with(user(AppUserPrincipal.from(second))).param("date", "2024-02-29"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(day.at("/entries/0/title").asText()).isEqualTo("방문 기록");
        assertThat(day.at("/entries/0/placeId").asLong()).isEqualTo(place.getId());
        assertThat(day.at("/entries/0/thumbnailId").asLong()).isEqualTo(photo.getId());
        assertThat(day.at("/entries/0").has("categories")).isFalse();
        mvc.perform(get(base + "/" + id + "/day").with(user(AppUserPrincipal.from(first))).param("date", "2024-02-29")).andExpect(status().isNotFound());
        String photoPath = base + "/" + id + "/photos/visit/" + photo.getId();
        mvc.perform(get(photoPath).with(user(AppUserPrincipal.from(first)))).andExpect(status().isNotFound());
        String mapPath = base + "/" + id + "/map";
        JsonNode map = mapper.readTree(mvc.perform(get(mapPath).with(user(AppUserPrincipal.from(second))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(map.path("readOnly").asBoolean()).isTrue();
        assertThat(map.at("/places/0/name").asText()).isEqualTo("공유 장소");
        assertThat(map.at("/visits/0/photos/0/photoUrl").asText()).startsWith(base + "/" + id + "/photos/visit/");
        String placePath = base + "/" + id + "/place-source/" + place.getId();
        JsonNode source = mapper.readTree(mvc.perform(get(placePath).with(user(AppUserPrincipal.from(second))).param("kind", "VISIT").param("recordId", visit.getId().toString()).param("date", "2024-02-29"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(source.at("/place/sourceTravelRecordId").isNull()).isTrue();
        assertThat(source.at("/visit/title").asText()).isEqualTo("방문 기록");
        mvc.perform(get(placePath).with(user(AppUserPrincipal.from(first))).param("kind", "VISIT").param("recordId", visit.getId().toString())).andExpect(status().isNotFound());
        mvc.perform(put(base + "/selections/" + id).with(user(AppUserPrincipal.from(second))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"selected\":false}")).andExpect(status().isNoContent());
        mvc.perform(get(base + "/" + id + "/day").with(user(AppUserPrincipal.from(second))).param("date", "2024-02-29")).andExpect(status().isNotFound());
        mvc.perform(get(photoPath).with(user(AppUserPrincipal.from(second)))).andExpect(status().isNotFound());
        mvc.perform(put(base + "/selections/" + id).with(user(AppUserPrincipal.from(second))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"selected\":true}")).andExpect(status().isNoContent());
        place.getCollections().clear(); place = places.saveAndFlush(place);
        mvc.perform(get(photoPath).with(user(AppUserPrincipal.from(second)))).andExpect(status().isNotFound());
        mvc.perform(get(placePath).with(user(AppUserPrincipal.from(second))).param("kind", "VISIT").param("recordId", visit.getId().toString())).andExpect(status().isNotFound());
        day = mapper.readTree(mvc.perform(get(base + "/" + id + "/day").with(user(AppUserPrincipal.from(second))).param("date", "2024-02-29"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(day.path("entries")).isEmpty();
        place.getCollections().add(collection); places.saveAndFlush(place);
        mapShares.revoke(first.getId(), share.id());
        mvc.perform(get(base + "/" + id + "/day").with(user(AppUserPrincipal.from(second))).param("date", "2024-02-29")).andExpect(status().isNotFound());
        mvc.perform(get(photoPath).with(user(AppUserPrincipal.from(second)))).andExpect(status().isNotFound());
        mvc.perform(get(mapPath).with(user(AppUserPrincipal.from(second)))).andExpect(status().isNotFound());
        var noVisits = mapShares.create(first.getId(), collection.getId(), new ShareRequest(false, 7));
        mvc.perform(post(base + "/links").with(user(AppUserPrincipal.from(second))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(mapper.createObjectNode().put("token", noVisits.token())))).andExpect(status().isNoContent());
        options = mapper.readTree(mvc.perform(get(base + "/options").with(user(AppUserPrincipal.from(second)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long restrictedId = options.at("/items/0/id").asLong();
        assertThat(options.at("/items/0/includesVisits").asBoolean()).isFalse();
        day = mapper.readTree(mvc.perform(get(base + "/" + restrictedId + "/day").with(user(AppUserPrincipal.from(second))).param("date", "2024-02-29"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(day.path("entries")).isEmpty();
        var expired = mapShareRows.findById(noVisits.id()).orElseThrow(); expired.setExpiresAt(LocalDateTime.now().minusMinutes(1)); mapShareRows.saveAndFlush(expired);
        mvc.perform(get(base + "/" + restrictedId + "/map").with(user(AppUserPrincipal.from(second)))).andExpect(status().isNotFound());
        options = mapper.readTree(mvc.perform(get(base + "/options").with(user(AppUserPrincipal.from(second)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(options.path("items")).isEmpty();
    }
    @Test void acceptedTravelRecordShareStopsBeingVisibleWhenGroupMembershipEnds() throws Exception {
        var plan = new TravelPlan(); plan.setOwner(first); plan.setName("그룹 여행"); plan.setStartDate(LocalDate.of(2024, 2, 28)); plan.setEndDate(LocalDate.of(2024, 3, 2)); plans.saveAndFlush(plan);
        var group = new TravelShareGroup(); group.setOwner(first); group.setName("가족"); group.setCreatedAt(LocalDateTime.now()); group.setUpdatedAt(LocalDateTime.now()); groups.saveAndFlush(group);
        var member = new TravelShareGroupMember(); member.setGroup(group); member.setMember(second); member.setCreatedAt(LocalDateTime.now()); members.saveAndFlush(member);
        var share = new RecordShare(); share.setGroupId(group.getId()); share.setGroupName(group.getName()); share.setKind(RecordShareKind.TRAVEL); share.setSourceId(plan.getId());
        share.setSender(first); share.setRecipient(second); share.setStatus(RecordShareStatus.ACCEPTED); share.setTitle(plan.getName()); share.setCreatedAt(LocalDateTime.now()); recordShares.saveAndFlush(share);
        String base = "/api/integrated-calendar/shared";
        mvc.perform(put(base + "/selections/record/" + share.getId()).with(user(AppUserPrincipal.from(second))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"selected\":true}")).andExpect(status().isNoContent());
        mvc.perform(get(base + "/summary/record/" + share.getId()).with(user(AppUserPrincipal.from(second))).param("from", "2024-02-01").param("to", "2024-03-09")).andExpect(status().isOk());
        members.deleteById(member.getId()); members.flush();
        mvc.perform(get(base + "/summary/record/" + share.getId()).with(user(AppUserPrincipal.from(second))).param("from", "2024-02-01").param("to", "2024-03-09")).andExpect(status().isNotFound());
        mvc.perform(get(base + "/day/record/" + share.getId()).with(user(AppUserPrincipal.from(second))).param("date", "2024-02-29")).andExpect(status().isNotFound());
    }
    private JsonNode summary(AppUser owner, String path) throws Exception {
        return mapper.readTree(mvc.perform(get(path).with(user(AppUserPrincipal.from(owner))).param("from", "2024-02-01").param("to", "2024-03-09"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode day(AppUser owner, String source) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/integrated-calendar/day/" + source).with(user(AppUserPrincipal.from(owner))).param("date", "2024-02-29"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
