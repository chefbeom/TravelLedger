package com.playdata.calen.ledger.domain.policy;

import static org.assertj.core.api.Assertions.*;

import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.model.LedgerTravelData.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;

class LedgerTravelPolicyTest {
    final Plan plan = new Plan(21L, 7L, "Trip", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
    @Test void reverseValidationOrderAndInclusiveDatesStayUnchanged() {
        assertThatThrownBy(() -> LedgerTravelPolicy.reverse(plan, entry(EntryType.INCOME, 8L, plan.start().minusDays(1), 99L))).hasMessage("Only expense ledger entries can be linked to travel records.");
        assertThatThrownBy(() -> LedgerTravelPolicy.reverse(plan, entry(EntryType.EXPENSE, 8L, plan.start().minusDays(1), 99L))).hasMessage("Travel plan owner does not match the ledger owner.");
        assertThatThrownBy(() -> LedgerTravelPolicy.reverse(plan, entry(EntryType.EXPENSE, 7L, plan.start().minusDays(1), 99L))).hasMessage("Ledger entry date must be within the selected travel period.");
        assertThatThrownBy(() -> LedgerTravelPolicy.reverse(plan, entry(EntryType.EXPENSE, 7L, plan.start(), 99L))).hasMessage("Ledger entry is already linked to another travel plan.");
        assertThatCode(() -> LedgerTravelPolicy.reverse(plan, entry(EntryType.EXPENSE, 7L, plan.start(), null))).doesNotThrowAnyException();
        assertThatCode(() -> LedgerTravelPolicy.reverse(plan, entry(EntryType.EXPENSE, 7L, plan.end(), 21L))).doesNotThrowAnyException();
    }
    @Test void foreignTupleRequiresCodeAmountAndRateAndKeepsTwoAndSixDigitRounding() {
        var value = LedgerTravelPolicy.travelValue(foreign(" jpy ", new BigDecimal("12.345"), new BigDecimal("1.2345678")), null);
        assertThat(value.currency()).isEqualTo("JPY"); assertThat(value.amount()).isEqualTo(new BigDecimal("12.35"));
        assertThat(value.rate()).isEqualTo(new BigDecimal("1.234568")); assertThat(value.amountKrw()).isEqualTo(new BigDecimal("123.46"));
        assertThat(value.memo()).isEmpty(); assertThat(value.shared()).isFalse(); assertThat(value.time()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(LedgerTravelPolicy.travelValue(foreign("JPY", new BigDecimal("12.345"), null), true).currency()).isEqualTo("KRW");
        assertThat(LedgerTravelPolicy.travelValue(foreign("KRW", new BigDecimal("12.345"), BigDecimal.TEN), false).amount()).isEqualTo(new BigDecimal("123.46"));
    }
    @Test void reverseUsesFullUntruncatedCategoryForTitleFallbackAndBlankDetailDoesNotFallBackToGroup() {
        var base = foreign(null, null, null);
        var longDetail = new Entry(base.id(), base.owner(), base.type(), base.date(), null, "  ", null, base.amount(), null, null, null,
                new Named(11L, "Group"), new Named(12L, "x".repeat(150)), new Ref(13L), null, null);
        var value = LedgerTravelPolicy.travelValue(longDetail, true);
        assertThat(value.category()).hasSize(80); assertThat(value.title()).hasSize(120); assertThat(value.shared()).isTrue();
        var blankDetail = new Entry(base.id(), base.owner(), base.type(), base.date(), null, null, null, base.amount(), null, null, null,
                new Named(11L, "Group"), new Named(12L, "  "), new Ref(13L), null, null);
        assertThat(LedgerTravelPolicy.travelValue(blankDetail, false).category()).isEqualTo("기타");
        assertThat(LedgerTravelPolicy.detailName("x".repeat(150))).hasSize(150);
        assertThat(LedgerTravelPolicy.createdDetailName("x".repeat(150))).hasSize(80);
    }
    private Entry foreign(String currency, BigDecimal amount, BigDecimal rate) {
        return new Entry(31L, 7L, EntryType.EXPENSE, plan.start(), null, "Title", null, new BigDecimal("123.456"), currency, amount, rate, new Named(11L, "Group"), null, new Ref(13L), null, null);
    }
    private Entry entry(EntryType type, Long owner, LocalDate date, Long linkedPlan) {
        return new Entry(31L, owner, type, date, null, null, null, BigDecimal.ONE, null, null, null, null, null, null, linkedPlan, null);
    }
}
