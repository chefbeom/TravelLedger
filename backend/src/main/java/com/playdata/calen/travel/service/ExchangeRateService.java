package com.playdata.calen.travel.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.dto.TravelExchangeRateResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class ExchangeRateService {

    private static final String KRW = "KRW";
    private static final String PROVIDER = "Frankfurter";

    private final RestClient restClient;
    private final Duration cacheDuration;
    private final Map<String, CachedRate> cache = new ConcurrentHashMap<>();

    public ExchangeRateService(
            RestClient.Builder restClientBuilder,
            @Value("${app.travel.exchange-rate-base-url:https://api.frankfurter.dev/v1}") String exchangeRateBaseUrl,
            @Value("${app.travel.exchange-rate-cache-minutes:30}") long cacheMinutes
    ) {
        this.restClient = restClientBuilder.baseUrl(exchangeRateBaseUrl).build();
        this.cacheDuration = Duration.ofMinutes(Math.max(cacheMinutes, 1));
    }

    public List<TravelExchangeRateResponse> getLatestRates(Collection<String> currencyCodes) {
        return currencyCodes.stream()
                .map(this::getLatestRateToKrw)
                .toList();
    }

    public BigDecimal getRequiredRateToKrw(String currencyCode) {
        TravelExchangeRateResponse response = getLatestRateToKrw(currencyCode);
        if (!response.available() || response.rateToKrw() == null) {
            throw new BadRequestException(currencyCode + " 환율을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        return response.rateToKrw();
    }

    public TravelExchangeRateResponse getLatestRateToKrw(String currencyCode) {
        String normalizedCurrency = currencyCode == null ? KRW : currencyCode.trim().toUpperCase();
        if (KRW.equals(normalizedCurrency)) {
            return new TravelExchangeRateResponse(KRW, BigDecimal.ONE, LocalDate.now(), true, PROVIDER);
        }

        CachedRate cachedRate = cache.get(normalizedCurrency);
        if (cachedRate != null && cachedRate.expiresAt().isAfter(Instant.now())) {
            return cachedRate.response();
        }

        try {
            ExchangeRateApiResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/latest")
                            .queryParam("base", normalizedCurrency)
                            .queryParam("symbols", KRW)
                            .build())
                    .retrieve()
                    .body(ExchangeRateApiResponse.class);

            BigDecimal rateToKrw = response != null && response.rates() != null ? response.rates().get(KRW) : null;
            if (rateToKrw == null) {
                return fallback(normalizedCurrency, cachedRate);
            }

            TravelExchangeRateResponse quote = new TravelExchangeRateResponse(
                    normalizedCurrency,
                    rateToKrw,
                    response.date() != null ? response.date() : LocalDate.now(),
                    true,
                    PROVIDER
            );
            cache.put(normalizedCurrency, new CachedRate(quote, Instant.now().plus(cacheDuration)));
            return quote;
        } catch (Exception exception) {
            log.warn("Failed to fetch exchange rate for {}", normalizedCurrency, exception);
            return fallback(normalizedCurrency, cachedRate);
        }
    }

    private TravelExchangeRateResponse fallback(String currencyCode, CachedRate cachedRate) {
        if (cachedRate != null) {
            return cachedRate.response();
        }
        return new TravelExchangeRateResponse(currencyCode, null, null, false, PROVIDER);
    }

    private record CachedRate(TravelExchangeRateResponse response, Instant expiresAt) {
    }

    private record ExchangeRateApiResponse(
            String base,
            LocalDate date,
            Map<String, BigDecimal> rates
    ) {
    }
}
