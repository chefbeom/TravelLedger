package com.playdata.calen.ledger.adapter.out.exchangerate;

import com.playdata.calen.ledger.application.port.out.LedgerExchangeRatePort;
import com.playdata.calen.ledger.domain.model.LedgerPreparationData.Rate;
import com.playdata.calen.travel.service.ExchangeRateService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class TravelLedgerExchangeRateAdapter implements LedgerExchangeRatePort {
    private final ExchangeRateService rates;
    @Override public Rate requiredRate(String currency, LocalDate date) {
        var quote = rates.getRequiredRateQuoteToKrw(currency, date);
        return new Rate(quote.rateToKrw(), quote.fetchedDate(), quote.provider());
    }
}
