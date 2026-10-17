package com.playdata.calen.travel.adapter.out.exchangerate;

import com.playdata.calen.travel.application.port.out.TravelRatePort;
import com.playdata.calen.travel.application.port.out.TravelReferenceRatesPort;
import com.playdata.calen.travel.domain.model.TravelReferenceData.Quote;
import com.playdata.calen.travel.service.ExchangeRateService;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TravelRateAdapter implements TravelRatePort, TravelReferenceRatesPort {
    private final ExchangeRateService rates;
    @Override public BigDecimal requiredRate(String currency) { return rates.getRequiredRateToKrw(currency); }
    @Override public List<Quote> latest(Collection<String> currencies) {
        return rates.getLatestRates(currencies).stream().map(q -> new Quote(q.currencyCode(), q.rateToKrw(), q.fetchedDate(), q.available(), q.provider())).toList();
    }
}
