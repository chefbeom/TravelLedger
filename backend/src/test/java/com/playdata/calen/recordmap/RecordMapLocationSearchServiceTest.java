package com.playdata.calen.recordmap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.recordmap.service.RecordMapLocationSearchService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

class RecordMapLocationSearchServiceTest {
    HttpServer server;
    WorkLeaseService leases;
    RecordMapLocationSearchService service;
    AtomicInteger requests;
    String response;
    int status;

    @BeforeEach void setup() throws Exception {
        leases = mock(WorkLeaseService.class);
        when(leases.tryReserveInterval(anyString(), any())).thenReturn(true);
        requests = new AtomicInteger(); status=200;
        response="[{\"name\":\"Seoul Station\",\"display_name\":\"Seoul, Korea\",\"lat\":\"37.55\",\"lon\":\"126.97\",\"address\":{\"country\":\"Korea\",\"city\":\"Seoul\"}}]";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/search", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("User-Agent")).isEqualTo("TravelLedger/test");
            assertThat(exchange.getRequestURI().getQuery()).contains("limit=6");
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        service = new RecordMapLocationSearchService(RestClient.builder(),new ObjectMapper(),leases,
            "http://127.0.0.1:"+server.getAddress().getPort()+"/reverse", "TravelLedger/test",1200);
    }
    @AfterEach void close() { server.stop(0); }
    @Test void explicitSearchUsesSharedProviderGateAndBoundedCache() {
        assertThat(service.search("Seoul")).hasSize(1).first().extracting("latitude").isEqualTo(37.55);
        assertThat(service.search("SEOUL")).hasSize(1);
        assertThat(requests.get()).isEqualTo(1);
        verify(leases,times(1)).tryReserveInterval("geocode-provider:127.0.0.1",Duration.ofMillis(1200));
    }
    @Test void rejectedInputAndRateLimitNeverCallProvider() {
        assertThatThrownBy(() -> service.search("x")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search("x".repeat(101))).isInstanceOf(BadRequestException.class);
        when(leases.tryReserveInterval(anyString(),any())).thenReturn(false);
        assertThatThrownBy(() -> service.search("Seoul")).isInstanceOf(ServiceUnavailableException.class);
        assertThat(requests.get()).isZero();
    }
    @Test void providerFailureIsGenericAndOversizedResponsesAreRejected() {
        status=500;
        assertThatThrownBy(() -> service.search("error address")).isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining("127.0.0.1");
        status=200; response=" ".repeat(65537);
        assertThatThrownBy(() -> service.search("large address")).isInstanceOf(ServiceUnavailableException.class);
    }
    @Test void invalidCoordinatesAreDiscardedAndResultsAreCapped() {
        response="[{\"name\":\"bad\",\"lat\":\"91\",\"lon\":\"0\"}," +
            String.join(",",java.util.Collections.nCopies(8,"{\"name\":\"valid\",\"lat\":\"37\",\"lon\":\"127\"}"))+"]";
        assertThat(service.search("address")).hasSize(6).allMatch(row -> row.name().equals("valid"));
    }
}
