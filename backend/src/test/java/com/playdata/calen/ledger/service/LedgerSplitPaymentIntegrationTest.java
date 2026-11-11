package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerTravelAdapter;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerAdjustmentType;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.dto.LedgerAdjustmentRequest;
import com.playdata.calen.ledger.dto.LedgerEntryBulkUpdateRequest;
import com.playdata.calen.ledger.dto.LedgerClassificationDeleteRequest;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.dto.LedgerSplitPaymentRequest;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ledger-split-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@AutoConfigureMockMvc
class LedgerSplitPaymentIntegrationTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
    @Autowired AppUserService users;
    @Autowired LedgerEntryService ledger;
    @Autowired LedgerAdjustmentService adjustments;
    @Autowired StatisticsService statistics;
    @Autowired PaymentMethodService paymentService;
    @Autowired JpaLedgerTravelAdapter travelAdapter;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CategoryGroupRepository groups;
    @Autowired PaymentMethodRepository payments;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test void splitIsAtomicIdempotentAndAggregatedByActualPaymentMethod() {
        Fixture f = fixture(); UUID requestId = UUID.randomUUID();
        var request = split(f, requestId, "60", "40", 3);
        var saved = ledger.createSplitPayment(f.id(), request);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).splitPaymentGroupId()).isEqualTo(requestId.toString());
        assertThat(saved.get(0).splitPaymentIndex()).isEqualTo(1);
        assertThat(saved.get(1).splitPaymentCount()).isEqualTo(2);
        assertThat(saved.get(0).installmentMonths()).isEqualTo(3);
        assertThat(saved.get(1).installmentMonths()).isNull();
        assertThat(ledger.createSplitPayment(f.id(), request)).extracting(item -> item.id()).containsExactly(saved.get(0).id(), saved.get(1).id());
        assertThatThrownBy(() -> ledger.createSplitPayment(f.id(), split(f, requestId, "50", "50", 3))).isInstanceOf(BadRequestException.class);
        assertThat(statistics.getOverview(f.id(), DATE, DATE).expense()).isEqualByComparingTo("100");
        Boolean travelLinkable = new TransactionTemplate(transactions).execute(status -> travelAdapter.ownedEntry(f.id(), saved.get(0).id()).isPresent());
        assertThat(travelLinkable).isFalse();
        var incomeEdit = new LedgerEntryRequest(DATE, null, "변경", null, new BigDecimal("60"), null, null, null,
                EntryType.INCOME, f.incomeGroupId(), null, null, null, null, 0);
        assertThatThrownBy(() -> ledger.update(f.id(), saved.get(0).id(), incomeEdit)).isInstanceOf(BadRequestException.class);
        var foreignEdit = new LedgerEntryRequest(DATE, null, "변경", null, new BigDecimal("60"), "USD", BigDecimal.ONE,
                new BigDecimal("1000"), EntryType.EXPENSE, f.expenseGroupId(), null, f.cardId(), null, null, 0);
        assertThatThrownBy(() -> ledger.update(f.id(), saved.get(0).id(), foreignEdit)).isInstanceOf(BadRequestException.class);
        var breakdown = statistics.getPaymentBreakdown(f.id(), DATE, DATE);
        assertThat(breakdown).anySatisfy(item -> { assertThat(item.kind()).isEqualTo(PaymentMethodKind.CARD); assertThat(item.totalAmount()).isEqualByComparingTo("60"); });
        assertThat(breakdown).anySatisfy(item -> { assertThat(item.kind()).isEqualTo(PaymentMethodKind.CASH); assertThat(item.totalAmount()).isEqualByComparingTo("40"); });

        Long cancelId = adjustments.create(f.id(), saved.get(0).id(), new LedgerAdjustmentRequest(
                LedgerAdjustmentType.PAYMENT_CANCELLATION, DATE.plusDays(1), null, null, null, null, null, null, null, UUID.randomUUID()));
        assertThat(statistics.getOverview(f.id(), DATE, DATE).expense()).isEqualByComparingTo("40");
        adjustments.revert(f.id(), cancelId);
        assertThat(statistics.getOverview(f.id(), DATE, DATE).expense()).isEqualByComparingTo("100");
        adjustments.create(f.id(), saved.get(1).id(), new LedgerAdjustmentRequest(LedgerAdjustmentType.REFUND, DATE.plusDays(2), null,
                new BigDecimal("40"), new BigDecimal("5"), BigDecimal.ZERO, f.incomeGroupId(), f.cashId(), null, UUID.randomUUID()));
        assertThat(statistics.getOverview(f.id(), DATE.plusDays(2), DATE.plusDays(2)).income()).isEqualByComparingTo("35");
    }

    @Test void badPartRollsBackEverythingAndOtherUsersMethodsCannotBeUsed() {
        Fixture f = fixture(); Fixture other = fixture();
        assertThatThrownBy(() -> ledger.createSplitPayment(f.id(), split(f, UUID.randomUUID(), "60", "39", 3))).isInstanceOf(BadRequestException.class);
        var invalid = new LedgerSplitPaymentRequest(UUID.randomUUID(), base(f, "100", null), List.of(
                new LedgerSplitPaymentRequest.Part(f.cardId(), new BigDecimal("60"), 3),
                new LedgerSplitPaymentRequest.Part(f.cashId(), new BigDecimal("40"), 3)));
        assertThatThrownBy(() -> ledger.createSplitPayment(f.id(), invalid)).isInstanceOf(BadRequestException.class);
        var foreignMethod = new LedgerSplitPaymentRequest(UUID.randomUUID(), base(f, "100", null), List.of(
                new LedgerSplitPaymentRequest.Part(f.cardId(), new BigDecimal("60"), null),
                new LedgerSplitPaymentRequest.Part(other.cashId(), new BigDecimal("40"), null)));
        assertThatThrownBy(() -> ledger.createSplitPayment(f.id(), foreignMethod)).isInstanceOf(NotFoundException.class);
        assertThat(ledger.getEntries(f.id(), DATE, DATE)).isEmpty();
    }

    @Test void installmentDoesNotDivideTheAmountAndCanBeEditedOrCleared() {
        Fixture f = fixture();
        var entry = ledger.create(f.id(), base(f, "100", 12));
        assertThat(entry.installmentMonths()).isEqualTo(12);
        assertThat(statistics.getOverview(f.id(), DATE, DATE).expense()).isEqualByComparingTo("100");
        assertThatThrownBy(() -> ledger.bulkUpdate(f.id(), new LedgerEntryBulkUpdateRequest(List.of(entry.id()), null, null, f.cashId())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> paymentService.delete(f.id(), f.cardId(), new LedgerClassificationDeleteRequest(null, null, f.cashId())))
                .isInstanceOf(BadRequestException.class);
        var cleared = ledger.update(f.id(), entry.id(), base(f, "100", 0));
        assertThat(cleared.installmentMonths()).isNull();
        assertThat(ledger.getChangeHistories(f.id(), 0, 20).totalElements()).isGreaterThan(0);
        Long historyId = ledger.getChangeHistories(f.id(), 0, 20).content().get(0).id();
        ledger.restoreChangeHistory(f.id(), historyId);
        assertThat(ledger.getEntries(f.id(), DATE, DATE).get(0).installmentMonths()).isEqualTo(12);
        assertThatThrownBy(() -> ledger.create(f.id(), base(f, "100", 1))).isInstanceOf(BadRequestException.class);
        assertThat(statistics.getOverview(f.id(), DATE, DATE).expense()).isEqualByComparingTo("100");
    }

    @Test void interestTypeIsPersistedForEachCardAndRestoredWithHistory() {
        Fixture f = fixture();
        var request = new LedgerSplitPaymentRequest(UUID.randomUUID(), base(f, "100", null), List.of(
                new LedgerSplitPaymentRequest.Part(f.cardId(), new BigDecimal("60"), 3, "FREE"),
                new LedgerSplitPaymentRequest.Part(f.cashId(), new BigDecimal("40"), null, null)));
        var saved = ledger.createSplitPayment(f.id(), request);
        assertThat(saved.get(0).installmentInterestType()).isEqualTo("FREE");
        assertThat(saved.get(1).installmentInterestType()).isNull();
        var changed = new LedgerEntryRequest(DATE, null, "변경", null, new BigDecimal("60"), null, null, null,
                EntryType.EXPENSE, f.expenseGroupId(), null, f.cardId(), null, null, 6, "INTEREST");
        assertThat(ledger.update(f.id(), saved.get(0).id(), changed).installmentInterestType()).isEqualTo("INTEREST");
        Long historyId = ledger.getChangeHistories(f.id(), 0, 20).content().get(0).id();
        ledger.restoreChangeHistory(f.id(), historyId);
        var restored = ledger.getEntries(f.id(), DATE, DATE).stream()
                .filter(item -> item.id().equals(saved.get(0).id())).findFirst().orElseThrow();
        assertThat(restored.installmentMonths()).isEqualTo(3);
        assertThat(restored.installmentInterestType()).isEqualTo("FREE");
        var invalid = new LedgerSplitPaymentRequest(UUID.randomUUID(), base(f, "100", null), List.of(
                new LedgerSplitPaymentRequest.Part(f.cardId(), new BigDecimal("60"), 3, "INVALID"),
                new LedgerSplitPaymentRequest.Part(f.cashId(), new BigDecimal("40"), null, null)));
        assertThatThrownBy(() -> ledger.createSplitPayment(f.id(), invalid)).isInstanceOf(BadRequestException.class);
    }

    @Test void simultaneousRetriesWithSameRequestIdNeverCreateDuplicateParts() throws Exception {
        Fixture f = fixture(); var request = split(f, UUID.randomUUID(), "60", "40", 3);
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return ledger.createSplitPayment(f.id(), request); });
            var second = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return ledger.createSplitPayment(f.id(), request); });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).extracting(item -> item.id())
                    .containsExactlyElementsOf(second.get(20, TimeUnit.SECONDS).stream().map(item -> item.id()).toList());
            assertThat(ledger.getEntries(f.id(), DATE, DATE)).hasSize(2);
        } finally { pool.shutdownNow(); }
    }

    @Test void httpCreatesTwoLinkedItemsForAuthenticatedOwner() throws Exception {
        Fixture f = fixture();
        mvc.perform(post("/api/entries/split-payments").with(user(AppUserPrincipal.from(f.owner()))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(split(f, UUID.randomUUID(), "60", "40", 2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].installmentMonths").value(2))
                .andExpect(jsonPath("$[0].splitPaymentCount").value(2)).andExpect(jsonPath("$[1].amount").value(40));
    }

    private LedgerSplitPaymentRequest split(Fixture f, UUID key, String first, String second, Integer months) {
        return new LedgerSplitPaymentRequest(key, base(f, "100", null), List.of(
                new LedgerSplitPaymentRequest.Part(f.cardId(), new BigDecimal(first), months),
                new LedgerSplitPaymentRequest.Part(f.cashId(), new BigDecimal(second), null)));
    }
    private LedgerEntryRequest base(Fixture f, String amount, Integer months) {
        return new LedgerEntryRequest(DATE, null, "분할 결제 테스트", null, new BigDecimal(amount), null, null, null,
                EntryType.EXPENSE, f.expenseGroupId(), null, f.cardId(), null, null, months);
    }
    private Fixture fixture() {
        AppUser owner = users.registerUser("split" + UUID.randomUUID().toString().replace("-", ""), "테스트", "test-password", "12345678");
        var expense = groups.findAllByOwnerIdAndEntryTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId(), EntryType.EXPENSE).get(0);
        var income = groups.findAllByOwnerIdAndEntryTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId(), EntryType.INCOME).get(0);
        var methods = payments.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId());
        Long card = methods.stream().filter(item -> item.getKind() == PaymentMethodKind.CARD).findFirst().orElseThrow().getId();
        Long cash = methods.stream().filter(item -> item.getKind() == PaymentMethodKind.CASH).findFirst().orElseThrow().getId();
        return new Fixture(owner, owner.getId(), expense.getId(), income.getId(), card, cash);
    }
    private record Fixture(AppUser owner, Long id, Long expenseGroupId, Long incomeGroupId, Long cardId, Long cashId) { }
}
