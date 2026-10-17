package com.playdata.calen.ledger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.ledger.adapter.out.entry.LinkedLedgerWriter;
import com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerTravelAdapter;
import com.playdata.calen.ledger.adapter.out.travel.TravelBridgeEffects;
import com.playdata.calen.ledger.application.port.in.ReflectTravelLedgerUseCase;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.repository.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Real JPA/writer/history/cache callbacks, isolated DB and mocked external systems only. */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:travel-ledger-reflection;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelLedgerReflectionIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired LedgerEntryRepository entries;
    @Autowired CategoryGroupRepository groups;
    @Autowired CategoryDetailRepository details;
    @Autowired PaymentMethodRepository payments;
    @Autowired LedgerEntryChangeHistoryRepository history;
    @Autowired ReflectTravelLedgerUseCase reflection;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean LinkedLedgerWriter writer;
    @MockitoSpyBean JpaLedgerTravelAdapter store;
    @MockitoSpyBean TravelBridgeEffects effects;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelClusterRefreshCoordinator coordinator;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean ExchangeRateService rates;
    LinkedLedgerWriter writerTarget;
    JpaLedgerTravelAdapter storeTarget;
    TravelBridgeEffects effectsTarget;
    record Fixture(long owner, long plan, long record, long entry, long group, long detail, long payment) {}

    @BeforeEach void privateDatabaseAndUnwrappedFaultInjectionTargets() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:travel-ledger-reflection");
        }
        writerTarget = AopTestUtils.getUltimateTargetObject(writer);
        storeTarget = AopTestUtils.getUltimateTargetObject(store);
        effectsTarget = AopTestUtils.getUltimateTargetObject(effects);
        reset(writerTarget, storeTarget, effectsTarget);
    }

    @Test void bothPostRoutesRequireAuthenticationAndCsrf() throws Exception {
        var f = fixture();
        for (var path : List.of(forward(f), reverse(f))) {
            mvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(post(path).with(user(principal(f)))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(redis, coordinator, storage, rates);
    }

    @Test void forwardWireContractSequentialUpsertAndTrashExclusionKeepTheExistingWriterAndHistory() throws Exception {
        var f = fixture();
        var first = postOk(f, forward(f)); long id = first.path("id").asLong();
        assertThat(first.size()).isEqualTo(21);
        assertThat(first.path("title").asText()).isEqualTo("[여행] Lunch");
        assertThat(first.path("entryTime").asText()).isEqualTo("12:34");
        assertThat(first.path("memo").asText()).isEqualTo(String.join(System.lineSeparator(), "여행: Trip", "장소: Cafe", "지역: Seoul", "국가: KR", "메모: Original memo"));
        assertThat(first.path("travelPlanId").asLong()).isEqualTo(f.plan());
        assertThat(first.path("travelRecordId").asLong()).isEqualTo(f.record());
        assertThat(historyCount(f)).isZero();
        tx(() -> { records.findById(f.record()).orElseThrow().setTitle("Updated"); return null; });
        assertThat(postOk(f, forward(f)).path("id").asLong()).isEqualTo(id);
        assertThat(entries.findById(id).orElseThrow().getTitle()).isEqualTo("[여행] Updated");
        assertThat(historyCount(f)).isEqualTo(1);
        tx(() -> { entries.findById(id).orElseThrow().setDeletedAt(LocalDateTime.now()); return null; });
        long replacement = postOk(f, forward(f)).path("id").asLong();
        assertThat(replacement).isNotEqualTo(id);
        assertThat(entries.findById(id).orElseThrow().getDeletedAt()).isNotNull();
        verifyNoInteractions(redis, coordinator, storage, rates);
    }

    @Test void forwardReactivatesExistingDefaultsWithoutDuplicatingOrReorderingThem() {
        var f = fixture();
        tx(() -> {
            var group = groups.findById(f.group()).orElseThrow(); group.setName("여행"); group.setActive(false); group.setDisplayOrder(9);
            var detail = details.findById(f.detail()).orElseThrow(); detail.setName("Food"); detail.setActive(false); detail.setDisplayOrder(8);
            var payment = payments.findById(f.payment()).orElseThrow(); payment.setName("여행 미지정"); payment.setActive(false); payment.setDisplayOrder(7);
            return null;
        });
        var result = reflection.toLedger(f.owner(), f.record());
        assertThat(result.group()).isEqualTo(f.group()); assertThat(result.detail()).isEqualTo(f.detail()); assertThat(result.payment()).isEqualTo(f.payment());
        assertThat(groups.findById(f.group()).orElseThrow().isActive()).isTrue();
        assertThat(details.findById(f.detail()).orElseThrow().isActive()).isTrue();
        assertThat(payments.findById(f.payment()).orElseThrow().isActive()).isTrue();
        assertThat(groups.findById(f.group()).orElseThrow().getDisplayOrder()).isEqualTo(9);
        verifyNoInteractions(redis, coordinator);
    }

    @Test void forwardScopedAndKindRejectionsDoNotAllocateAnyDefaults() throws Exception {
        var f = fixture(); var stranger = fixture(); long groupCount = groups.count();
        mvc.perform(post(forward(f)).with(user(principal(stranger))).with(csrf())).andExpect(status().isNotFound());
        tx(() -> { records.findById(f.record()).orElseThrow().setRecordType(TravelRecordType.MEMORY); return null; });
        mvc.perform(post(forward(f)).with(user(principal(f))).with(csrf())).andExpect(status().isNotFound());
        tx(() -> { records.findById(f.record()).orElseThrow().setRecordType(null); return null; });
        mvc.perform(post(forward(f)).with(user(principal(f))).with(csrf())).andExpect(status().isBadRequest());
        assertThat(groups.count()).isEqualTo(groupCount); verifyNoInteractions(redis, coordinator);
    }

    @Test void reverseWireContractForeignRoundingAndReusedRecordPreserveGeographyAndSharedState() throws Exception {
        var f = fixture();
        tx(() -> {
            var e = entries.findById(f.entry()).orElseThrow(); e.setEntryTime(null); e.setMemo("  memo  ");
            e.setForeignCurrencyCode("JPY"); e.setForeignAmount(new BigDecimal("100.1234")); e.setExchangeRateToKrw(new BigDecimal("9.1")); e.setAmount(new BigDecimal("911.12"));
            return null;
        });
        var first = postOk(f, reverse(f)); long id = first.path("id").asLong();
        assertThat(first.size()).isEqualTo(19); assertThat(first.path("expenseTime").asText()).isEqualTo("00:00:00");
        assertThat(first.path("amount").decimalValue()).isEqualByComparingTo("100.12");
        assertThat(first.path("exchangeRateToKrw").decimalValue()).isEqualByComparingTo("9.100000");
        assertThat(first.path("amountKrw").decimalValue()).isEqualByComparingTo("911.12");
        assertThat(entries.findById(f.entry()).orElseThrow().getEntryTime()).isNull();
        tx(() -> { var r = records.findById(id).orElseThrow(); r.setCountry("Retained"); r.setLatitude(new BigDecimal("37.5")); r.setSharedWithCommunity(true); return null; });
        var again = postOk(f, reverse(f)); assertThat(again.path("id").asLong()).isEqualTo(id);
        assertThat(again.path("country").asText()).isEqualTo("Retained");
        assertThat(records.findById(id).orElseThrow().getSharedWithCommunity()).isTrue();
        // The original writer suppresses a second history row when the backlink update is a no-op.
        assertThat(historyCount(f)).isEqualTo(1);
        verify(redis, times(4)).delete(keys(f)); verify(coordinator, times(2)).markDirtyInCurrentTransaction(f.owner()); verify(coordinator, times(2)).dispatch(f.owner());
        verifyNoInteractions(rates, storage);
    }

    @Test void reverseMissingForeignMemoryAndNullKindPointersCreateNewRecordsWithoutTouchingTheTargets() {
        for (int mode = 0; mode < 4; mode++) {
            var f = fixture(); var foreign = fixture(); int kind = mode;
            long pointer = mode == 0 ? Long.MAX_VALUE : mode == 1 ? foreign.record() : f.record();
            tx(() -> {
                var e = entries.findById(f.entry()).orElseThrow(); e.setTravelRecordId(pointer);
                if (kind >= 2) records.findById(f.record()).orElseThrow().setRecordType(kind == 2 ? TravelRecordType.MEMORY : null);
                return null;
            });
            long count = records.count(); var result = reflection.toTravel(f.owner(), f.plan(), f.entry());
            assertThat(result.id()).isNotEqualTo(pointer); assertThat(records.count()).isEqualTo(count + 1);
            assertThat(records.findById(f.record()).orElseThrow().getTitle()).isEqualTo("Lunch");
            assertThat(records.findById(foreign.record()).orElseThrow().getTitle()).isEqualTo("Lunch");
        }
    }

    @Test void reverseValidationAndInclusiveDateBoundariesRemainOwnerScoped() throws Exception {
        var f = fixture(); var other = fixture();
        mvc.perform(post(reverse(f)).with(user(principal(other))).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/travel/plans/{p}/ledger-entries/{e}/record", f.plan(), other.entry()).with(user(principal(f))).with(csrf())).andExpect(status().isBadRequest());
        tx(() -> { entries.findById(f.entry()).orElseThrow().setEntryType(EntryType.INCOME); return null; });
        assertThatThrownBy(() -> reflection.toTravel(f.owner(), f.plan(), f.entry())).hasMessage("Only expense ledger entries can be linked to travel records.");
        tx(() -> { var e = entries.findById(f.entry()).orElseThrow(); e.setEntryType(EntryType.EXPENSE); e.setEntryDate(LocalDate.of(2026, 9, 30)); return null; });
        assertThatThrownBy(() -> reflection.toTravel(f.owner(), f.plan(), f.entry())).hasMessage("Ledger entry date must be within the selected travel period.");
        tx(() -> { var e = entries.findById(f.entry()).orElseThrow(); e.setEntryDate(LocalDate.of(2026, 10, 1)); e.setDeletedAt(LocalDateTime.now()); return null; });
        assertThatThrownBy(() -> reflection.toTravel(f.owner(), f.plan(), f.entry())).hasMessage("Ledger entry not found.");
        tx(() -> { entries.findById(f.entry()).orElseThrow().setDeletedAt(null); return null; });
        assertThat(reflection.toTravel(f.owner(), f.plan(), f.entry()).date()).isEqualTo(LocalDate.of(2026, 10, 1));
        tx(() -> { entries.findById(f.entry()).orElseThrow().setEntryDate(LocalDate.of(2026, 10, 31)); return null; });
        assertThat(reflection.toTravel(f.owner(), f.plan(), f.entry()).date()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test void reverseOtherPlanAndOtherRecordLinksRejectBeforeMutation() {
        var f = fixture();
        long anotherPlan = tx(() -> { var p = plan(users.findById(f.owner()).orElseThrow()); return plans.save(p).getId(); });
        tx(() -> { entries.findById(f.entry()).orElseThrow().setTravelPlanId(anotherPlan); return null; });
        assertThatThrownBy(() -> reflection.toTravel(f.owner(), f.plan(), f.entry())).hasMessage("Ledger entry is already linked to another travel plan.");
        tx(() -> {
            var e = entries.findById(f.entry()).orElseThrow(); e.setTravelPlanId(null); e.setTravelRecordId(f.record());
            records.findById(f.record()).orElseThrow().setPlan(plans.findById(anotherPlan).orElseThrow()); return null;
        });
        assertThatThrownBy(() -> reflection.toTravel(f.owner(), f.plan(), f.entry())).hasMessage("Ledger entry is already linked to another travel record.");
        assertThat(historyCount(f)).isZero(); verifyNoInteractions(redis, coordinator);
    }

    @Test void forwardWriterFailureRollsBackNewDefaultReferencesAndReverseWriterFailureRollsBackTravelMutation() {
        var f = fixture(); long groupCount = groups.count(), detailCount = details.count(), paymentCount = payments.count(), recordCount = records.count();
        doThrow(new IllegalStateException("isolated write failure")).when(writerTarget).create(eq(f.owner()), any());
        assertThatThrownBy(() -> reflection.toLedger(f.owner(), f.record())).hasMessage("isolated write failure");
        assertThat(groups.count()).isEqualTo(groupCount); assertThat(details.count()).isEqualTo(detailCount); assertThat(payments.count()).isEqualTo(paymentCount);
        doThrow(new IllegalStateException("isolated write failure")).when(writerTarget).update(eq(f.owner()), eq(f.entry()), any());
        assertThatThrownBy(() -> reflection.toTravel(f.owner(), f.plan(), f.entry())).hasMessage("isolated write failure");
        assertThat(records.count()).isEqualTo(recordCount); assertThat(entries.findById(f.entry()).orElseThrow().getTravelRecordId()).isNull();
        tx(() -> { entries.findById(f.entry()).orElseThrow().setTravelRecordId(f.record()); return null; });
        assertThatThrownBy(() -> reflection.toTravel(f.owner(), f.plan(), f.entry())).hasMessage("isolated write failure");
        assertThat(records.findById(f.record()).orElseThrow().getTitle()).isEqualTo("Lunch");
        assertThat(historyCount(f)).isZero(); verifyNoInteractions(redis, coordinator);
    }

    @Test void invalidResponseColorRollsBackTheAlreadySavedRecordLedgerLinkAndHistoryBeforeEffects() {
        var f = fixture(); long count = records.count();
        tx(() -> { plans.findById(f.plan()).orElseThrow().setColorHex("invalid"); return null; });
        assertThatThrownBy(() -> reflection.toTravel(f.owner(), f.plan(), f.entry())).hasMessage("Travel color must use #RRGGBB format.");
        verify(writerTarget).update(eq(f.owner()), eq(f.entry()), any());
        assertThat(records.count()).isEqualTo(count); assertThat(entries.findById(f.entry()).orElseThrow().getTravelRecordId()).isNull();
        assertThat(historyCount(f)).isZero(); verifyNoInteractions(redis, coordinator);
    }

    @Test void failureAfterInvalidationRollsBackSqlAndSuppressesAfterCommitEvictionAndDispatch() {
        var f = fixture(); long count = records.count();
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("isolated effect failure"); }).when(effectsTarget).travelChanged(f.owner());
        assertThatThrownBy(() -> reflection.toTravel(f.owner(), f.plan(), f.entry())).hasMessage("isolated effect failure");
        assertThat(records.count()).isEqualTo(count); assertThat(entries.findById(f.entry()).orElseThrow().getTravelRecordId()).isNull();
        assertThat(historyCount(f)).isZero(); verify(redis).delete(keys(f));
        verify(coordinator).markDirtyInCurrentTransaction(f.owner()); verify(coordinator, never()).dispatch(any());
    }

    @Test void outerTransactionMergesDirtyRequestsAndDispatchesOnlyAfterCommitWithWritableMandatoryAdapters() {
        var f = fixture();
        assertThatThrownBy(() -> store.ownedPlan(f.owner(), f.plan())).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> writer.update(f.owner(), f.entry(), null)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> effects.travelChanged(f.owner())).isInstanceOf(IllegalTransactionStateException.class);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse(); return call.callRealMethod();
        }).when(writerTarget).update(eq(f.owner()), eq(f.entry()), any());
        tx(() -> {
            long first = reflection.toTravel(f.owner(), f.plan(), f.entry()).id();
            assertThat(reflection.toTravel(f.owner(), f.plan(), f.entry()).id()).isEqualTo(first);
            verify(coordinator).markDirtyInCurrentTransaction(f.owner()); verify(coordinator, never()).dispatch(any());
            verify(redis, times(2)).delete(keys(f)); return null;
        });
        verify(coordinator).dispatch(f.owner()); verify(redis, times(4)).delete(keys(f));
    }

    private JsonNode postOk(Fixture f, String path) throws Exception {
        return json.readTree(mvc.perform(post(path).with(user(principal(f))).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private String forward(Fixture f) { return "/api/travel/records/" + f.record() + "/ledger-entry"; }
    private String reverse(Fixture f) { return "/api/travel/plans/" + f.plan() + "/ledger-entries/" + f.entry() + "/record"; }
    private String[] keys(Fixture f) { return new String[] { "travel:plans:" + f.owner(), "travel:portfolio:" + f.owner(), "travel:mymap:overview:" + f.owner(), "travel:public-trips:overview" }; }
    private AppUserPrincipal principal(Fixture f) { return AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()); }
    private long historyCount(Fixture f) { return history.findAllByOwnerIdOrderByCreatedAtDescIdDesc(f.owner(), Pageable.unpaged()).getTotalElements(); }
    private <T> T tx(Supplier<T> work) { return new TransactionTemplate(transactions).execute(status -> work.get()); }
    private TravelPlan plan(AppUser owner) {
        var p = new TravelPlan(); p.setOwner(owner); p.setName("Trip"); p.setStartDate(LocalDate.of(2026, 10, 1)); p.setEndDate(LocalDate.of(2026, 10, 31)); return p;
    }
    private Fixture fixture() {
        return tx(() -> {
            var u = new AppUser(); u.setLoginId("reflection-" + UUID.randomUUID()); u.setDisplayName("Fixture"); u.setPasswordHash("test-only-hash"); users.save(u);
            var p = plans.save(plan(u));
            var r = new TravelExpenseRecord(); r.setPlan(p); r.setRecordType(TravelRecordType.LEDGER); r.setExpenseDate(p.getStartDate()); r.setExpenseTime(LocalTime.of(12, 34));
            r.setCategory("Food"); r.setTitle("Lunch"); r.setAmount(new BigDecimal("12000")); r.setAmountKrw(new BigDecimal("12000")); r.setCurrencyCode("KRW"); r.setExchangeRateToKrw(BigDecimal.ONE);
            r.setCountry("KR"); r.setRegion("Seoul"); r.setPlaceName("Cafe"); r.setMemo("Original memo"); records.save(r);
            var g = new CategoryGroup(); g.setOwner(u); g.setName("Meals"); g.setEntryType(EntryType.EXPENSE); groups.save(g);
            var d = new CategoryDetail(); d.setGroup(g); d.setName("Lunch"); details.save(d);
            var m = new PaymentMethod(); m.setOwner(u); m.setName("Card"); m.setKind(PaymentMethodKind.CARD); payments.save(m);
            var e = new LedgerEntry(); e.setOwner(u); e.setEntryDate(p.getStartDate()); e.setEntryTime(LocalTime.of(10, 20)); e.setTitle("Household lunch"); e.setMemo("Ledger memo");
            e.setAmount(new BigDecimal("12000")); e.setEntryType(EntryType.EXPENSE); e.setCategoryGroup(g); e.setCategoryDetail(d); e.setPaymentMethod(m); entries.save(e);
            return new Fixture(u.getId(), p.getId(), r.getId(), e.getId(), g.getId(), d.getId(), m.getId());
        });
    }
}
