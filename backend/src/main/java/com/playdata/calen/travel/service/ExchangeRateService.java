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
        return getRequiredRateToKrw(currencyCode, null);
    }

    public BigDecimal getRequiredRateToKrw(String currencyCode, LocalDate rateDate) {
        return getRequiredRateQuoteToKrw(currencyCode, rateDate).rateToKrw();
    }

    public TravelExchangeRateResponse getRequiredRateQuoteToKrw(String currencyCode, LocalDate rateDate) {
        TravelExchangeRateResponse response = getRateToKrw(currencyCode, rateDate);
        if (!response.available() || response.rateToKrw() == null) {
            throw new BadRequestException(currencyCode + " exchange rate is unavailable. Please try again later.");
        }
        return response;
    }

    public TravelExchangeRateResponse getLatestRateToKrw(String currencyCode) {
        return getRateToKrw(currencyCode, null);
    }

    public TravelExchangeRateResponse getRateToKrw(String currencyCode, LocalDate rateDate) {
        String normalizedCurrency = normalizeCurrencyCode(currencyCode);
        LocalDate resolvedRateDate = normalizeRateDate(rateDate);
        if (KRW.equals(normalizedCurrency)) {
            return new TravelExchangeRateResponse(KRW, BigDecimal.ONE, resolvedRateDate, true, PROVIDER);
        }

        String cacheKey = normalizedCurrency + ":" + resolvedRateDate;
        CachedRate cachedRate = cache.get(cacheKey);
        if (cachedRate != null && cachedRate.expiresAt().isAfter(Instant.now())) {
            return cachedRate.response();
        }

        try {
            ExchangeRateApiResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(rateDate == null ? "/latest" : "/" + resolvedRateDate)
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
                    response.date() != null ? response.date() : resolvedRateDate,
                    true,
                    PROVIDER
            );
            cache.put(cacheKey, new CachedRate(quote, Instant.now().plus(cacheDuration)));
            return quote;
        } catch (Exception exception) {
            log.warn("Failed to fetch exchange rate for {}", normalizedCurrency, exception);
            return fallback(normalizedCurrency, cachedRate);
        }
    }

    private String normalizeCurrencyCode(String currencyCode) {
        String normalized = currencyCode == null ? KRW : currencyCode.trim().toUpperCase();
        return normalized.isBlank() ? KRW : normalized;
    }

    private LocalDate normalizeRateDate(LocalDate rateDate) {
        LocalDate today = LocalDate.now();
        if (rateDate == null || rateDate.isAfter(today)) {
            return today;
        }
        return rateDate;
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
