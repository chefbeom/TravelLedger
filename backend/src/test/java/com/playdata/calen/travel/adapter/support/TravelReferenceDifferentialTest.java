package com.playdata.calen.travel.adapter.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.travel.adapter.out.exchangerate.TravelRateAdapter;
import com.playdata.calen.travel.adapter.out.persistence.JpaTravelReferenceAdapter;
import com.playdata.calen.travel.application.service.ReadTravelReferenceService;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.dto.TravelExchangeRateResponse;
import com.playdata.calen.travel.fixture.TravelReferenceLegacyOracle;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.ExchangeRateService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class TravelReferenceDifferentialTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Test void catalogSourcesKeepPerSourceSortingCaseSensitiveDeduplicationNullKindsAndAllSevenJsonFields() throws Exception {
        for (int seed = 0; seed < 64; seed++) {
            var users = mock(AppUserService.class); var budgets = mock(TravelBudgetItemRepository.class); var records = mock(TravelExpenseRecordRepository.class); var rates = mock(ExchangeRateService.class);
            var labels = Arrays.asList(null, " ", "Food", "food", "  Custom  ", "a", "A", "서울", "\u2003");
            var budget = new ArrayList<TravelBudgetItem>(); var all = new ArrayList<TravelExpenseRecord>();
            for (int i = 0; i < labels.size(); i++) {
                var b = new TravelBudgetItem(); b.setCategory(labels.get((i + seed) % labels.size())); budget.add(b);
                var r = new TravelExpenseRecord(); r.setRecordType(i % 3 == 0 ? null : i % 3 == 1 ? TravelRecordType.LEDGER : TravelRecordType.MEMORY);
                r.setCategory(labels.get((i + seed) % labels.size())); r.setCountry(labels.get((i + 1 + seed) % labels.size())); r.setRegion(labels.get((i + 2 + seed) % labels.size())); r.setPlaceName(labels.get((i + 3 + seed) % labels.size())); all.add(r);
            }
            when(budgets.findAllByPlanOwnerId(7L)).thenReturn(budget); when(records.findAllByPlanOwnerId(7L)).thenReturn(all);
            var old = new TravelReferenceLegacyOracle(users, budgets, records, rates);
            var modern = new ReadTravelReferenceService(new JpaTravelReferenceAdapter(users, budgets, records), new TravelRateAdapter(rates));
            equal(old.getCategoryCatalog(7L), TravelReferenceMapper.catalog(modern.catalog(7L))); verifyNoInteractions(rates);
        }
    }
    @Test void currencyParsingOrderValidationAndQuoteWireValuesMatchForLocalesBlankSegmentsAndUnavailableQuotes() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            for (var locale : List.of(Locale.ROOT, Locale.forLanguageTag("tr-TR"))) {
                Locale.setDefault(locale);
                for (String input : Arrays.asList(null, "", " \t ", ",", ",,", "usd", "jpy,usd,jpy", "usd,", ",usd", "usd,,jpy", "usd, ", " usd , krw ", "XXX", "BAD1", "inr", "ııı")) {
                    var users = mock(AppUserService.class); var budgets = mock(TravelBudgetItemRepository.class); var records = mock(TravelExpenseRecordRepository.class); var rates = mock(ExchangeRateService.class);
                    when(rates.getLatestRates(anyCollection())).thenAnswer(call -> {
                        Collection<String> codes = call.getArgument(0);
                        return codes.stream().map(code -> new TravelExchangeRateResponse(code, "XXX".equals(code) ? null : new BigDecimal("1.2345678"), "XXX".equals(code) ? null : LocalDate.of(2026, 10, 17), !"XXX".equals(code), "fixture")).toList();
                    });
                    var old = new TravelReferenceLegacyOracle(users, budgets, records, rates);
                    var modern = new ReadTravelReferenceService(new JpaTravelReferenceAdapter(users, budgets, records), new TravelRateAdapter(rates));
                    Object expected = null; RuntimeException error = null;
                    try { expected = old.getExchangeRates(7L, input); } catch (RuntimeException e) { error = e; }
                    if (error != null) {
                        var captured = error;
                        assertThatThrownBy(() -> modern.rates(7L, input)).isExactlyInstanceOf(captured.getClass()).hasMessage(captured.getMessage()); verifyNoInteractions(rates);
                    } else equal(expected, modern.rates(7L, input).stream().map(TravelReferenceMapper::quote).toList());
                }
            }
        } finally { Locale.setDefault(previous); }
    }
    void equal(Object old, Object modern) throws Exception { assertThat(json.writeValueAsString(modern)).isEqualTo(json.writeValueAsString(old)); }
}
