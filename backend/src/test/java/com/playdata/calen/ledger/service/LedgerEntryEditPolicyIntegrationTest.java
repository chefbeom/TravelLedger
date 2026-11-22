package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerAdjustmentType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.dto.LedgerAdjustmentRequest;
import com.playdata.calen.ledger.dto.LedgerEntryBulkUpdateRequest;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ledger-edit-policy-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class LedgerEntryEditPolicyIntegrationTest {
    private static final LocalDate PURCHASE = LocalDate.of(2026, 1, 10);
    private static final LocalDate SETTLEMENT = LocalDate.of(2026, 3, 15);
    @Autowired AppUserService users;
    @Autowired LedgerAdjustmentService adjustments;
    @Autowired LedgerEntryService entryService;
    @Autowired StatisticsService statistics;
    @Autowired LedgerEntryRepository entries;
    @Autowired CategoryGroupRepository groups;
    @Autowired PaymentMethodRepository payments;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test void revertedEntryAllowsFullEditAndKeepsVoidedHistory() {
        Fixture f = fixture();
        Long original = purchase(f);
        Long record = adjustments.create(f.ownerId(), original, cancel(UUID.randomUUID()));
        adjustments.revert(f.ownerId(), record);
        LocalDate newDate = SETTLEMENT.plusDays(1);

        var updated = entryService.update(f.ownerId(), original, request(f, newDate, "수정된 결제", "150", EntryType.EXPENSE, f.expenseGroupId(), f.paymentId()));
        assertThat(updated.amount()).isEqualByComparingTo("150");
        var group = entryService.getAdjustmentGroup(f.ownerId(), original);
        assertThat(group.adjustments()).hasSize(1);
        assertThat(group.adjustments().get(0).amount()).isEqualByComparingTo("100");
        assertThat(group.adjustments().get(0).adjustmentVoidedAt()).isNotNull();
        assertThat(statistics.getOverview(f.ownerId(), newDate, newDate).expense()).isEqualByComparingTo("150");

        var tooMuch = new LedgerAdjustmentRequest(LedgerAdjustmentType.REFUND, newDate, null, new BigDecimal("151"),
                BigDecimal.ZERO, BigDecimal.ZERO, f.incomeGroupId(), f.paymentId(), null, UUID.randomUUID());
        assertThatThrownBy(() -> adjustments.create(f.ownerId(), original, tooMuch)).isInstanceOf(BadRequestException.class);
        var exact = new LedgerAdjustmentRequest(LedgerAdjustmentType.REFUND, newDate, null, new BigDecimal("150"),
                BigDecimal.ZERO, BigDecimal.ZERO, f.incomeGroupId(), f.paymentId(), null, UUID.randomUUID());
        assertThat(adjustments.create(f.ownerId(), original, exact)).isNotNull();
    }

    @Test void revertedEntryMayChangeType() {
        Fixture f = fixture();
        Long original = purchase(f);
        adjustments.revert(f.ownerId(), adjustments.create(f.ownerId(), original, cancel(UUID.randomUUID())));
        var updated = entryService.update(f.ownerId(), original,
                request(f, PURCHASE, "수입으로 변경", "100", EntryType.INCOME, f.incomeGroupId(), null));
        assertThat(updated.entryType()).isEqualTo(EntryType.INCOME);
        var refund = new LedgerAdjustmentRequest(LedgerAdjustmentType.REFUND, SETTLEMENT, null, new BigDecimal("10"),
                BigDecimal.ZERO, BigDecimal.ZERO, f.incomeGroupId(), f.paymentId(), null, UUID.randomUUID());
        assertThatThrownBy(() -> adjustments.create(f.ownerId(), original, refund)).isInstanceOf(BadRequestException.class);
    }

    @Test void activeCancellationAllowsOnlyNonFinancialFields() {
        Fixture f = fixture();
        Long original = purchase(f);
        adjustments.create(f.ownerId(), original, cancel(UUID.randomUUID()));

        var edited = entryService.update(f.ownerId(), original, new LedgerEntryRequest(PURCHASE.minusDays(1),
                java.time.LocalTime.of(9, 30), "제목 수정", "메모 수정", new BigDecimal("100"), null, null, null,
                EntryType.EXPENSE, f.expenseGroupId(), null, f.paymentId(), null, null, null, null));
        assertThat(edited.title()).isEqualTo("제목 수정");
        assertThat(edited.entryDate()).isEqualTo(PURCHASE.minusDays(1));
        assertThat(edited.cancelledAt()).isNotNull();
        assertThat(edited.financiallyExcluded()).isTrue();
        assertThat(edited.amount()).isEqualByComparingTo("100");

        LocalDate date = PURCHASE.minusDays(1);
        expectLocked(f, original, "금액", request(f, date, "제목 수정", "120", EntryType.EXPENSE, f.expenseGroupId(), f.paymentId()));
        expectLocked(f, original, "수입/지출 구분", request(f, date, "제목 수정", "100", EntryType.INCOME, f.incomeGroupId(), f.paymentId()));
        expectLocked(f, original, "결제수단", request(f, date, "제목 수정", "100", EntryType.EXPENSE, f.expenseGroupId(), f.otherPaymentId()));
        expectLocked(f, original, "할부", withInstallment(request(f, date, "제목 수정", "100", EntryType.EXPENSE, f.expenseGroupId(), f.paymentId()), 3));
        expectLocked(f, original, "여행 연결", withTravelPlan(request(f, date, "제목 수정", "100", EntryType.EXPENSE, f.expenseGroupId(), f.paymentId()), 999L));
        var unchanged = entries.findById(original).orElseThrow();
        assertThat(unchanged.getAmount()).isEqualByComparingTo("100");
        assertThat(unchanged.getEntryType()).isEqualTo(EntryType.EXPENSE);

        assertThatThrownBy(() -> entryService.update(f.ownerId(), original,
                request(f, SETTLEMENT.plusDays(1), "제목 수정", "100", EntryType.EXPENSE, f.expenseGroupId(), f.paymentId())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("2026-03-15");
    }

    @Test void activeRefundLocksAmountAndKeepsRefundedPrincipal() {
        Fixture f = fixture();
        Long original = purchase(f);
        adjustments.create(f.ownerId(), original, refund(f, "30", UUID.randomUUID()));
        assertThatThrownBy(() -> entryService.update(f.ownerId(), original,
                request(f, PURCHASE, "결제", "20", EntryType.EXPENSE, f.expenseGroupId(), f.paymentId())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("금액");
        assertThat(entries.findById(original).orElseThrow().getRefundedPrincipal()).isEqualByComparingTo("30");
        var renamed = entryService.update(f.ownerId(), original,
                request(f, PURCHASE, "제목만 변경", "100", EntryType.EXPENSE, f.expenseGroupId(), f.paymentId()));
        assertThat(renamed.title()).isEqualTo("제목만 변경");
        assertThat(renamed.refundedPrincipal()).isEqualByComparingTo("30");
    }

    @Test void foreignLimitedEditKeepsAmountAndRateMetadata() {
        Fixture f = fixture();
        var created = entryService.create(f.ownerId(), new LedgerEntryRequest(PURCHASE, null, "해외 결제", null, new BigDecimal("1"),
                "USD", new BigDecimal("10"), new BigDecimal("1300"), EntryType.EXPENSE, f.expenseGroupId(), null, f.paymentId(), null, null));
        Long id = created.id();
        var stored = entries.findById(id).orElseThrow();
        BigDecimal amount = stored.getAmount();
        LocalDate rateDate = stored.getExchangeRateDate();
        String provider = stored.getExchangeRateProvider();
        adjustments.create(f.ownerId(), id, refund(f, "1000", UUID.randomUUID()));

        var updated = entryService.update(f.ownerId(), id, new LedgerEntryRequest(PURCHASE.minusDays(2), null, "해외 결제", null,
                amount, "USD", new BigDecimal("10"), new BigDecimal("1300"), EntryType.EXPENSE, f.expenseGroupId(), null, f.paymentId(), null, null));
        assertThat(updated.amount()).isEqualByComparingTo(amount);
        var after = entries.findById(id).orElseThrow();
        assertThat(after.getExchangeRateDate()).isEqualTo(rateDate);
        assertThat(after.getExchangeRateProvider()).isEqualTo(provider);
        assertThat(after.getEntryDate()).isEqualTo(PURCHASE.minusDays(2));
    }

    @Test void adjustmentRecordsAreImmutable() {
        Fixture f = fixture();
        Long original = purchase(f);
        Long record = adjustments.create(f.ownerId(), original, cancel(UUID.randomUUID()));
        assertThatThrownBy(() -> entryService.update(f.ownerId(), record,
                request(f, SETTLEMENT, "x", "100", EntryType.EXPENSE, f.expenseGroupId(), f.paymentId())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> entryService.delete(f.ownerId(), record, false)).isInstanceOf(BadRequestException.class);
    }

    @Test void deleteBlockedWhileActive() {
        Fixture f = fixture();
        Long cancelled = purchase(f);
        adjustments.create(f.ownerId(), cancelled, cancel(UUID.randomUUID()));
        assertThatThrownBy(() -> entryService.delete(f.ownerId(), cancelled, false))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("삭제할 수 없습니다");
        Long refunded = purchase(f);
        adjustments.create(f.ownerId(), refunded, refund(f, "10", UUID.randomUUID()));
        assertThatThrownBy(() -> entryService.delete(f.ownerId(), refunded, false))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("삭제할 수 없습니다");
    }

    @Test void deleteRevertedCascadesToTrashAndRestoresTogether() {
        Fixture f = fixture();
        Long original = purchase(f);
        Long record = adjustments.create(f.ownerId(), original, cancel(UUID.randomUUID()));
        adjustments.revert(f.ownerId(), record);

        entryService.delete(f.ownerId(), original, false);
        assertThat(entryService.getEntries(f.ownerId(), PURCHASE, SETTLEMENT)).isEmpty();
        var trash = entryService.getDeletedEntries(f.ownerId(), 0, 50);
        assertThat(trash.content()).extracting(LedgerEntryResponse::id).containsExactly(original);
        assertThatThrownBy(() -> entryService.restore(f.ownerId(), record)).isInstanceOf(BadRequestException.class);

        entryService.restore(f.ownerId(), original);
        var group = entryService.getAdjustmentGroup(f.ownerId(), original);
        assertThat(group.adjustments()).hasSize(1);
        assertThat(group.adjustments().get(0).adjustmentVoidedAt()).isNotNull();
    }

    @Test void permanentDeleteRemovesHistory() {
        Fixture f = fixture();
        Long original = purchase(f);
        Long record = adjustments.create(f.ownerId(), original, cancel(UUID.randomUUID()));
        adjustments.revert(f.ownerId(), record);
        entryService.delete(f.ownerId(), original, true);
        assertThat(entries.findById(original)).isEmpty();
        assertThat(entries.findById(record)).isEmpty();
    }

    @Test void bulkAndSplitStayStrict() {
        Fixture f = fixture();
        Long original = purchase(f);
        adjustments.revert(f.ownerId(), adjustments.create(f.ownerId(), original, cancel(UUID.randomUUID())));
        assertThatThrownBy(() -> entryService.bulkUpdate(f.ownerId(),
                new LedgerEntryBulkUpdateRequest(List.of(original), f.expenseGroupId(), null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("일괄 수정");
    }

    @Test void httpUpdateRequiresOwner() throws Exception {
        Fixture f = fixture(); Fixture other = fixture();
        Long original = purchase(f);
        var body = request(f, PURCHASE, "타인 수정", "100", EntryType.EXPENSE, f.expenseGroupId(), f.paymentId());
        mvc.perform(put("/api/entries/{id}", original).with(user(AppUserPrincipal.from(other.owner())))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isNotFound());
    }

    private void expectLocked(Fixture f, Long id, String field, LedgerEntryRequest request) {
        assertThatThrownBy(() -> entryService.update(f.ownerId(), id, request))
                .isInstanceOf(BadRequestException.class).hasMessageContaining(field);
    }

    private LedgerEntryRequest request(Fixture f, LocalDate date, String title, String amount, EntryType type, Long groupId, Long paymentId) {
        return new LedgerEntryRequest(date, null, title, null, new BigDecimal(amount), null, null, null,
                type, groupId, null, paymentId, null, null, null, null);
    }

    private LedgerEntryRequest withInstallment(LedgerEntryRequest r, int months) {
        return new LedgerEntryRequest(r.entryDate(), r.entryTime(), r.title(), r.memo(), r.amount(), r.foreignCurrencyCode(),
                r.foreignAmount(), r.exchangeRateToKrw(), r.entryType(), r.categoryGroupId(), r.categoryDetailId(),
                r.paymentMethodId(), r.travelPlanId(), r.travelRecordId(), months, r.installmentInterestType());
    }

    private LedgerEntryRequest withTravelPlan(LedgerEntryRequest r, Long planId) {
        return new LedgerEntryRequest(r.entryDate(), r.entryTime(), r.title(), r.memo(), r.amount(), r.foreignCurrencyCode(),
                r.foreignAmount(), r.exchangeRateToKrw(), r.entryType(), r.categoryGroupId(), r.categoryDetailId(),
                r.paymentMethodId(), planId, r.travelRecordId(), r.installmentMonths(), r.installmentInterestType());
    }

    private Fixture fixture() {
        AppUser owner = users.registerUser("policy" + UUID.randomUUID().toString().replace("-", ""), "테스트", "test-password", "12345678");
        var expense = groups.findAllByOwnerIdAndEntryTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId(), EntryType.EXPENSE).get(0);
        var income = groups.findAllByOwnerIdAndEntryTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId(), EntryType.INCOME).get(0);
        var methods = payments.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId()).stream()
                .filter(item -> !"-".equals(item.getName())).toList();
        return new Fixture(owner, owner.getId(), expense.getId(), income.getId(), methods.get(0).getId(), methods.get(1).getId());
    }

    private Long purchase(Fixture f) {
        var entry = new LedgerEntry(); entry.setOwner(f.owner()); entry.setEntryDate(PURCHASE); entry.setTitle("결제");
        entry.setAmount(new BigDecimal("100")); entry.setEntryType(EntryType.EXPENSE);
        entry.setCategoryGroup(groups.getReferenceById(f.expenseGroupId())); entry.setPaymentMethod(payments.getReferenceById(f.paymentId()));
        return entries.saveAndFlush(entry).getId();
    }

    private LedgerAdjustmentRequest cancel(UUID key) {
        return new LedgerAdjustmentRequest(LedgerAdjustmentType.PAYMENT_CANCELLATION, SETTLEMENT, null, null, null, null, null, null, null, key);
    }

    private LedgerAdjustmentRequest refund(Fixture f, String principal, UUID key) {
        return new LedgerAdjustmentRequest(LedgerAdjustmentType.REFUND, SETTLEMENT, null, new BigDecimal(principal),
                BigDecimal.ZERO, BigDecimal.ZERO, f.incomeGroupId(), f.paymentId(), null, key);
    }

    private record Fixture(AppUser owner, Long ownerId, Long expenseGroupId, Long incomeGroupId, Long paymentId, Long otherPaymentId) {}
}
