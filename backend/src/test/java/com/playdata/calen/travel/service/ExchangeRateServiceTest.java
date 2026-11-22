package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ExchangeRateServiceTest {
    private static final String BODY = "{\"base\":\"USD\",\"date\":\"2026-11-20\",\"rates\":{\"KRW\":1400.5}}";

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) server.stop(0);
    }

    private String start(boolean slow) throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            if (slow) {
                try {
                    release.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void returnsRateAndCachesFreshValue() throws Exception {
        ExchangeRateService service = new ExchangeRateService(RestClient.builder(), start(false), 1L);

        var first = service.getRateToKrw("USD", null);
        var second = service.getRateToKrw("USD", null);

        assertThat(first.available()).isTrue();
        assertThat(first.rateToKrw()).isEqualByComparingTo(new BigDecimal("1400.5"));
        assertThat(second).isEqualTo(first);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void slowProviderTimesOutWithinReadTimeout() throws Exception {
        ExchangeRateService service = new ExchangeRateService(RestClient.builder(), start(true), 1L);

        long startedAt = System.nanoTime();
        var response = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> service.getRateToKrw("USD", null));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(response.available()).isFalse();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(7));
    }

    @Test
    void staleValueIsReturnedWhenProviderFailsAfterExpiry() throws Exception {
        ExchangeRateService service = new ExchangeRateService(RestClient.builder(), start(false), Duration.ofMillis(1));

        var first = service.getRateToKrw("USD", null);
        assertThat(first.available()).isTrue();
        Thread.sleep(20);
        status = 500;
        var second = service.getRateToKrw("USD", null);

        assertThat(requests.get()).isEqualTo(2);
        assertThat(second.available()).isTrue();
        assertThat(second.rateToKrw()).isEqualByComparingTo(first.rateToKrw());
    }

    @Test
    void cacheIsBounded() throws Exception {
        ExchangeRateService service = new ExchangeRateService(RestClient.builder(), start(false), 1L);

        LocalDate day = LocalDate.now().minusDays(1);
        for (int i = 0; i < 600; i++) service.getRateToKrw("USD", day.minusDays(i));

        assertThat(requests.get()).isEqualTo(600);
        assertThat(service.cacheSizeForTest()).isLessThanOrEqualTo(512);
    }
}
