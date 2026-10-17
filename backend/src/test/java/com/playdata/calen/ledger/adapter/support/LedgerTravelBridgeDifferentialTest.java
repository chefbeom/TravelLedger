package com.playdata.calen.ledger.adapter.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.ledger.adapter.out.entry.LinkedLedgerWriter;
import com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerTravelAdapter;
import com.playdata.calen.ledger.application.port.out.TravelBridgeEffectsPort;
import com.playdata.calen.ledger.application.service.ReflectTravelLedgerService;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.dto.*;
import com.playdata.calen.ledger.fixture.LedgerTravelBridgeLegacyOracle;
import com.playdata.calen.ledger.repository.*;
import com.playdata.calen.ledger.service.LedgerEntryService;
import com.playdata.calen.travel.adapter.support.TravelLedgerReflectionMapper;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.policy.TravelPlanPolicy;
import com.playdata.calen.travel.dto.TravelExpenseRecordResponse;
import com.playdata.calen.travel.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LedgerTravelBridgeDifferentialTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Test void forwardWireRequestAndAllocationRulesMatchTheFrozenServiceAcross64Inputs() throws Exception {
        for (int seed = 0; seed < 64; seed++) {
            var f = new Fixture(seed);
            var expected = f.old.upsertExpenseRecord(7L, f.original);
            f.group.setActive(false); f.detail.setActive(false); f.payment.setActive(false); f.modern = true;
            var actual = LedgerTravelMapper.response(f.service.toLedger(7L, 41L));
            equal(expected, actual); equal(f.requests.get(0), f.requests.get(1));
            assertThat(f.operations.get(1)).isEqualTo(f.operations.get(0));
            if (seed % 3 == 0) { assertThat(f.group.isActive()).isTrue(); assertThat(f.detail.isActive()).isTrue(); assertThat(f.payment.isActive()).isTrue(); }
            verifyNoInteractions(f.effects);
        }
    }
    @Test void reverseMutationAmountsGeographySharedStateAndBackLinkRequestMatchAcross64Inputs() throws Exception {
        for (int seed = 0; seed < 64; seed++) {
            var f = new Fixture(seed);
            var expectedRecord = f.old.upsertLedgerEntry(7L, f.plan, 31L); var expected = response(expectedRecord);
            f.modern = true; f.saved = null;
            var actual = TravelLedgerReflectionMapper.response(f.service.toTravel(7L, 21L, 31L));
            equal(expected, actual); equal(f.requests.get(0), f.requests.get(1));
            assertThat(f.saved.getSharedWithCommunity()).isEqualTo(expectedRecord.getSharedWithCommunity());
            assertThat(f.operations).containsExactly("update:7:31", "update:7:31");
            verify(f.effects).travelChanged(7L);
        }
    }
    void equal(Object old, Object modern) throws Exception { assertThat(json.writeValueAsString(modern)).isEqualTo(json.writeValueAsString(old)); }
    TravelExpenseRecordResponse response(TravelExpenseRecord r) {
        var p = r.getPlan();
        return new TravelExpenseRecordResponse(r.getId(), p.getId(), p.getName(), TravelPlanPolicy.color(p.getColorHex()), r.getRecordType(),
                r.getExpenseDate(), r.getExpenseTime(), r.getCategory(), r.getTitle(), r.getAmount(), r.getCurrencyCode(), r.getExchangeRateToKrw(),
                r.getAmountKrw(), r.getCountry(), r.getRegion(), r.getPlaceName(), r.getLatitude(), r.getLongitude(), r.getMemo());
    }
    static class Fixture {
        final LedgerEntryService ledger = mock(LedgerEntryService.class);
        final LedgerEntryRepository entries = mock(LedgerEntryRepository.class);
        final CategoryGroupRepository groups = mock(CategoryGroupRepository.class);
        final CategoryDetailRepository details = mock(CategoryDetailRepository.class);
        final PaymentMethodRepository payments = mock(PaymentMethodRepository.class);
        final TravelExpenseRecordRepository records = mock(TravelExpenseRecordRepository.class);
        final TravelPlanRepository plans = mock(TravelPlanRepository.class);
        final AppUserRepository users = mock(AppUserRepository.class);
        final TravelBridgeEffectsPort effects = mock(TravelBridgeEffectsPort.class);
        final LedgerTravelBridgeLegacyOracle old;
        final ReflectTravelLedgerService service;
        final TravelPlan plan = new TravelPlan();
        final CategoryGroup group = new CategoryGroup();
        final CategoryDetail detail = new CategoryDetail();
        final PaymentMethod payment = new PaymentMethod();
        final LedgerEntry entry = new LedgerEntry();
        final TravelExpenseRecord original, modernRecord;
        final List<LedgerEntryRequest> requests = new ArrayList<>();
        final List<String> operations = new ArrayList<>();
        boolean modern;
        TravelExpenseRecord saved;

        Fixture(int seed) {
            var owner = new AppUser(); owner.setId(7L); owner.setLoginId("fixture-owner"); owner.setDisplayName("Owner");
            plan.setId(21L); plan.setOwner(owner); plan.setName("  Trip  "); plan.setColorHex("#abcdef"); plan.setStartDate(LocalDate.of(2026, 10, 1)); plan.setEndDate(LocalDate.of(2026, 10, 31));
            group.setId(11L); group.setOwner(owner); group.setEntryType(EntryType.EXPENSE); group.setName("여행"); group.setActive(false);
            detail.setId(12L); detail.setGroup(group); detail.setName(seed % 3 == 0 ? " " : "Detail " + seed); detail.setActive(false);
            payment.setId(13L); payment.setOwner(owner); payment.setName("여행 미지정"); payment.setKind(PaymentMethodKind.OTHER); payment.setActive(false);
            original = travel(seed); modernRecord = travel(seed);
            entry.setId(31L); entry.setOwner(owner); entry.setEntryType(EntryType.EXPENSE); entry.setEntryDate(plan.getStartDate().plusDays(seed % 30));
            entry.setEntryTime(seed % 2 == 0 ? null : LocalTime.of(9, 15)); entry.setTitle(seed % 3 == 0 ? " " : "Ledger title " + seed);
            entry.setMemo(seed % 3 == 0 ? null : seed % 3 == 1 ? " \t " : "  " + "memo".repeat(150) + "  ");
            entry.setAmount(new BigDecimal("123.456")); entry.setForeignCurrencyCode(currency(seed));
            entry.setForeignAmount((seed & 4) != 0 ? new BigDecimal("12.345") : null); entry.setExchangeRateToKrw((seed & 8) != 0 ? new BigDecimal("1.2345678") : null);
            entry.setCategoryGroup(group); entry.setCategoryDetail(seed % 2 == 0 ? detail : null); entry.setPaymentMethod(payment);
            entry.setTravelPlanId(seed % 2 == 0 ? 21L : null);
            Long pointer = null;
            if ((seed & 32) != 0) pointer = 41L; else if (seed % 2 != 0) pointer = 999L;
            entry.setTravelRecordId(pointer);
            when(users.getReferenceById(7L)).thenReturn(owner); when(plans.findByIdAndOwnerId(21L, 7L)).thenReturn(Optional.of(plan)); when(plans.getReferenceById(21L)).thenReturn(plan);
            when(records.findByIdAndPlanOwnerId(41L, 7L)).thenReturn(Optional.of(original));
            when(entries.findByIdAndOwnerIdAndDeletedAtIsNull(31L, 7L)).thenReturn(Optional.of(entry));
            when(entries.findByOwnerIdAndTravelRecordIdAndDeletedAtIsNull(7L, 41L)).thenReturn(seed % 2 == 0 ? Optional.of(entry) : Optional.empty());
            when(groups.findFirstByOwnerIdAndEntryTypeAndNameIgnoreCaseOrderByIdAsc(eq(7L), eq(EntryType.EXPENSE), anyString())).thenReturn(seed % 3 == 0 ? Optional.of(group) : Optional.empty());
            when(details.findFirstByGroupIdAndNameIgnoreCaseOrderByIdAsc(eq(11L), anyString())).thenReturn(seed % 3 == 0 ? Optional.of(detail) : Optional.empty());
            when(payments.findFirstByOwnerIdAndNameIgnoreCaseOrderByIdAsc(eq(7L), anyString())).thenReturn(seed % 3 == 0 ? Optional.of(payment) : Optional.empty());
            when(groups.save(any())).thenAnswer(c -> { CategoryGroup v = c.getArgument(0); v.setId(11L); return v; });
            when(details.save(any())).thenAnswer(c -> { CategoryDetail v = c.getArgument(0); v.setId(12L); return v; });
            when(payments.save(any())).thenAnswer(c -> { PaymentMethod v = c.getArgument(0); v.setId(13L); return v; });
            when(groups.getReferenceById(11L)).thenReturn(group);
            when(records.findByIdAndPlanOwnerIdAndRecordType(41L, 7L, TravelRecordType.LEDGER)).thenAnswer(c -> Optional.of(modern ? modernRecord : original));
            when(records.save(any())).thenAnswer(c -> { saved = c.getArgument(0); if (saved.getId() == null) saved.setId(51L); return saved; });
            when(records.getReferenceById(anyLong())).thenAnswer(c -> saved != null && saved.getId().equals(c.getArgument(0)) ? saved : modernRecord);
            when(ledger.create(eq(7L), any())).thenAnswer(c -> { operations.add("create:7"); return write(c.getArgument(1)); });
            when(ledger.update(eq(7L), eq(31L), any())).thenAnswer(c -> { operations.add("update:7:31"); return write(c.getArgument(2)); });
            old = new LedgerTravelBridgeLegacyOracle(ledger, entries, groups, details, payments, records);
            service = new ReflectTravelLedgerService(new JpaLedgerTravelAdapter(users, plans, records, entries, groups, details, payments), new LinkedLedgerWriter(ledger), effects);
        }
        LedgerEntryResponse write(LedgerEntryRequest r) {
            requests.add(r);
            return new LedgerEntryResponse(31L, r.entryDate(), r.entryTime(), r.title(), r.memo(), r.amount(), r.foreignCurrencyCode(), r.foreignAmount(),
                    r.exchangeRateToKrw(), LocalDate.of(2026, 10, 15), "fixture", r.entryType(), r.categoryGroupId(), "Group", r.categoryDetailId(), "Detail",
                    r.paymentMethodId(), "Payment", PaymentMethodKind.OTHER, r.travelPlanId(), r.travelRecordId());
        }
        TravelExpenseRecord travel(int seed) {
            var r = new TravelExpenseRecord(); r.setId(41L); r.setPlan(plan); r.setRecordType(TravelRecordType.LEDGER);
            r.setExpenseDate(plan.getStartDate()); r.setExpenseTime(seed % 2 == 0 ? null : LocalTime.NOON); r.setCategory(seed % 3 == 0 ? " \t " : "Food");
            r.setTitle(seed % 2 == 0 ? null : "title".repeat(40)); r.setMemo(seed % 3 == 0 ? null : "  " + "memo".repeat(150) + "  ");
            r.setAmount(new BigDecimal("12.345")); r.setAmountKrw(seed % 5 == 0 ? null : new BigDecimal("123.456")); r.setCurrencyCode(currency(seed)); r.setExchangeRateToKrw(new BigDecimal("1.2345678"));
            r.setCountry("KR"); r.setRegion(" Seoul "); r.setPlaceName("Place"); r.setLatitude(new BigDecimal("37.5")); r.setLongitude(new BigDecimal("127"));
            r.setSharedWithCommunity(seed % 3 == 0 ? null : seed % 3 == 1); return r;
        }
        String currency(int seed) { return switch(seed % 4) { case 0 -> null; case 1 -> " jpy "; case 2 -> "KRW"; default -> " eur "; }; }
    }
}
