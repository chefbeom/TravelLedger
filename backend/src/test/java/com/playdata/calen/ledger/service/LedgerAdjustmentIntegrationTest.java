package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AppUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerAdjustmentType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.dto.LedgerAdjustmentRequest;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ledger-adjust-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class LedgerAdjustmentIntegrationTest {
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

    @Test void cancellationPreservesBothRecordsButExcludesThemFromAllTotalsAndCanBeReverted() {
        Fixture f = fixture();
        Long original = purchase(f);
        var request = cancel(UUID.randomUUID());
        Long event = adjustments.create(f.ownerId(), original, request);
        assertThat(adjustments.create(f.ownerId(), original, request)).isEqualTo(event);

        assertThat(statistics.getOverview(f.ownerId(), PURCHASE, PURCHASE).expense()).isEqualByComparingTo("0");
        assertThat(statistics.getOverview(f.ownerId(), SETTLEMENT, SETTLEMENT).expense()).isEqualByComparingTo("0");
        assertThat(statistics.getCategoryBreakdown(f.ownerId(), PURCHASE, SETTLEMENT, EntryType.EXPENSE)).isEmpty();
        assertThat(entryService.getEntries(f.ownerId(), PURCHASE, PURCHASE).get(0).cancelledAt()).isNotNull();
        var search = entryService.searchEntries(f.ownerId(), PURCHASE, SETTLEMENT, null, null, null, null, null,
                false, false, false, null, null, true, "DATE_DESC", 0, 100);
        assertThat(search.content()).hasSize(2);
        assertThat(search.summary().expense()).isEqualByComparingTo("0");
        String csv = new String(LedgerCsvFormatter.format(search.content()), StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain("결제 취소", "결제\"");
        var history = entryService.getAdjustmentHistory(f.ownerId(), original);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).adjustmentType()).isEqualTo(LedgerAdjustmentType.PAYMENT_CANCELLATION);
        assertThat(history.get(0).financiallyExcluded()).isTrue();
        assertThatThrownBy(() -> entryService.delete(f.ownerId(), original, false)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> entryService.update(f.ownerId(), event, null)).isInstanceOf(BadRequestException.class);

        adjustments.revert(f.ownerId(), event);
        adjustments.revert(f.ownerId(), event); // safe to retry
        assertThat(statistics.getOverview(f.ownerId(), PURCHASE, PURCHASE).expense()).isEqualByComparingTo("100");
        assertThat(entryService.getAdjustmentHistory(f.ownerId(), original).get(0).adjustmentVoidedAt()).isNotNull();
    }

    @Test void refundUsesActualReceiptDateMethodAndSeparateDeductionAndExtra() {
        Fixture f = fixture();
        Long original = purchase(f);
        UUID key = UUID.randomUUID();
        var request = refund(f, "100", "10", "3", key);
        Long refundId = adjustments.create(f.ownerId(), original, request);
        assertThat(adjustments.create(f.ownerId(), original, request)).isEqualTo(refundId);
        assertThatThrownBy(() -> adjustments.create(f.ownerId(), original, refund(f, "100", "9", "3", key)))
                .isInstanceOf(BadRequestException.class);
        assertThat(statistics.getOverview(f.ownerId(), PURCHASE, PURCHASE).expense()).isEqualByComparingTo("100");
        assertThat(statistics.getOverview(f.ownerId(), SETTLEMENT, SETTLEMENT).income()).isEqualByComparingTo("93");
        var refund = entryService.getEntries(f.ownerId(), SETTLEMENT, SETTLEMENT).get(0);
        assertThat(refund.amount()).isEqualByComparingTo("93");
        assertThat(refund.adjustmentPrincipal()).isEqualByComparingTo("100");
        assertThat(refund.adjustmentDeduction()).isEqualByComparingTo("10");
        assertThat(refund.adjustmentExtra()).isEqualByComparingTo("3");
        assertThat(refund.paymentMethodId()).isEqualTo(f.paymentId());
        assertThat(refund.paymentMethodName()).isNotEqualTo("-");
        String csv = new String(LedgerCsvFormatter.format(java.util.List.of(refund)), StandardCharsets.UTF_8);
        assertThat(csv).contains("환불: 결제", "93.00");
        assertThatThrownBy(() -> adjustments.create(f.ownerId(), original, cancel(UUID.randomUUID())))
                .isInstanceOf(BadRequestException.class);
        adjustments.revert(f.ownerId(), refundId);
        assertThat(statistics.getOverview(f.ownerId(), SETTLEMENT, SETTLEMENT).income()).isEqualByComparingTo("0");
        assertThat(entryService.getEntries(f.ownerId(), PURCHASE, PURCHASE).get(0).refundedPrincipal()).isEqualByComparingTo("0");
    }

    @Test void partialRefundsCannotExceedOriginalAndAreIsolatedPerUser() {
        Fixture f = fixture(); Fixture other = fixture();
        Long original = purchase(f);
        adjustments.create(f.ownerId(), original, refund(f, "40", "0", "0", UUID.randomUUID()));
        assertThatThrownBy(() -> adjustments.create(f.ownerId(), original, refund(f, "61", "0", "0", UUID.randomUUID())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> adjustments.create(other.ownerId(), original, cancel(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> entryService.getAdjustmentHistory(other.ownerId(), original)).isInstanceOf(NotFoundException.class);
        adjustments.create(f.ownerId(), original, refund(f, "60", "0", "5", UUID.randomUUID()));
        assertThat(statistics.getOverview(f.ownerId(), SETTLEMENT, SETTLEMENT).income()).isEqualByComparingTo("105");
        assertThat(entryService.getEntries(f.ownerId(), PURCHASE, PURCHASE).get(0).refundedPrincipal()).isEqualByComparingTo("100");
    }

    @Test void invalidMoneyDatesAndForeignCatalogsDoNotModifyTheOriginal() {
        Fixture f = fixture(); Fixture other = fixture(); Long original = purchase(f);
        assertThatThrownBy(() -> adjustments.create(f.ownerId(), original, refund(f, "100", "101", "0", UUID.randomUUID())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> adjustments.create(f.ownerId(), original, refund(f, "100.001", "0", "0", UUID.randomUUID())))
                .isInstanceOf(BadRequestException.class);
        var early = new LedgerAdjustmentRequest(LedgerAdjustmentType.PAYMENT_CANCELLATION, PURCHASE.minusDays(1),
                null, null, null, null, null, null, null, UUID.randomUUID());
        assertThatThrownBy(() -> adjustments.create(f.ownerId(), original, early)).isInstanceOf(BadRequestException.class);
        var foreignGroup = new LedgerAdjustmentRequest(LedgerAdjustmentType.REFUND, SETTLEMENT, null,
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, other.incomeGroupId(), f.paymentId(), null, UUID.randomUUID());
        assertThatThrownBy(() -> adjustments.create(f.ownerId(), original, foreignGroup)).isInstanceOf(BadRequestException.class);
        assertThat(entryService.getAdjustmentHistory(f.ownerId(), original)).isEmpty();
        var unchanged = entryService.getEntries(f.ownerId(), PURCHASE, PURCHASE).get(0);
        assertThat(unchanged.refundedPrincipal()).isEqualByComparingTo("0");
        assertThat(unchanged.financiallyExcluded()).isFalse();
        assertThat(unchanged.hasAdjustmentHistory()).isFalse();
    }

    @Test void httpAdjustmentAndHistoryRequireTheOwner() throws Exception {
        Fixture f = fixture(); Fixture other = fixture(); Long original = purchase(f);
        mvc.perform(post("/api/entries/{id}/adjustments", original).with(user(AppUserPrincipal.from(f.owner())))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(refund(f, "100", "10", "3", UUID.randomUUID()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").isNumber());
        mvc.perform(get("/api/entries/{id}/adjustments", original).with(user(AppUserPrincipal.from(f.owner()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].adjustmentType").value("REFUND"))
                .andExpect(jsonPath("$[0].amount").value(93.0));
        mvc.perform(get("/api/entries/{id}/adjustments", original).with(user(AppUserPrincipal.from(other.owner()))))
                .andExpect(status().isNotFound());
    }

    @Test void concurrentRefundsCannotDoubleSpendTheSamePrincipal() throws Exception {
        Fixture f = fixture(); Long original = purchase(f);
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> attemptRefund(f, original, start));
            var second = pool.submit(() -> attemptRefund(f, original, start));
            start.countDown();
            assertThat((first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
            assertThat(entryService.getAdjustmentHistory(f.ownerId(), original)).hasSize(1);
        } finally { pool.shutdownNow(); }
    }

    private boolean attemptRefund(Fixture f, Long original, CountDownLatch start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try { adjustments.create(f.ownerId(), original, refund(f, "70", "0", "0", UUID.randomUUID())); return true; }
        catch (BadRequestException expected) { return false; }
    }
    private Fixture fixture() {
        AppUser owner = users.registerUser("adjust" + UUID.randomUUID().toString().replace("-", ""), "테스트", "test-password", "12345678");
        var expense = groups.findAllByOwnerIdAndEntryTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId(), EntryType.EXPENSE).get(0);
        var income = groups.findAllByOwnerIdAndEntryTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId(), EntryType.INCOME).get(0);
        var payment = payments.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId()).stream()
                .filter(item -> !"-".equals(item.getName())).findFirst().orElseThrow();
        return new Fixture(owner, owner.getId(), expense.getId(), income.getId(), payment.getId());
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
    private LedgerAdjustmentRequest refund(Fixture f, String principal, String deduction, String extra, UUID key) {
        return new LedgerAdjustmentRequest(LedgerAdjustmentType.REFUND, SETTLEMENT, null, new BigDecimal(principal),
                new BigDecimal(deduction), new BigDecimal(extra), f.incomeGroupId(), f.paymentId(), null, key);
    }
    private record Fixture(AppUser owner, Long ownerId, Long expenseGroupId, Long incomeGroupId, Long paymentId) {}
}
