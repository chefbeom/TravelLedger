package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.TooManyRequestsException;
import com.playdata.calen.travel.dto.TravelReverseGeocodeResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TravelReverseGeocodeDispatcherTest {
    @Test
    void mergesCoordinatesAndCancellingOneCallerDoesNotCancelOthers() throws Exception {
        TravelReverseGeocodeService provider = mock(TravelReverseGeocodeService.class);
        TravelReverseGeocodeDispatcher dispatcher = new TravelReverseGeocodeDispatcher(provider, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TravelReverseGeocodeResponse address = new TravelReverseGeocodeResponse("대한민국", "서울", "광화문");
        when(provider.reverseGeocode(37.5, 127.0)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
            return address;
        });
        try {
            CompletableFuture<TravelReverseGeocodeResponse> first = dispatcher.reverseGeocode(37.5, 127.0);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<TravelReverseGeocodeResponse> second = dispatcher.reverseGeocode(37.5, 127.0);
            first.cancel(true);
            release.countDown();
            assertThat(second.get(2, TimeUnit.SECONDS)).isEqualTo(address);
            assertThat(dispatcher.reverseGeocode(37.5, 127.0).get(2, TimeUnit.SECONDS)).isEqualTo(address);
            verify(provider, times(1)).reverseGeocode(37.5, 127.0);
        } finally {
            release.countDown();
            dispatcher.shutdown();
        }
    }

    @Test
    void fullProviderQueueRejectsWithoutCallingExternalServer() throws Exception {
        TravelReverseGeocodeService provider = mock(TravelReverseGeocodeService.class);
        TravelReverseGeocodeDispatcher dispatcher = new TravelReverseGeocodeDispatcher(provider, 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(provider.reverseGeocode(37.5, 127.0)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return TravelReverseGeocodeResponse.empty();
        });
        try {
            dispatcher.reverseGeocode(37.5, 127.0);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> dispatcher.reverseGeocode(38.0, 128.0).join())
                    .hasCauseInstanceOf(TooManyRequestsException.class);
        } finally {
            release.countDown();
            dispatcher.shutdown();
        }
    }

    @Test
    void invalidCoordinatesAreRejectedBeforeScheduling() {
        TravelReverseGeocodeService provider = mock(TravelReverseGeocodeService.class);
        TravelReverseGeocodeDispatcher dispatcher = new TravelReverseGeocodeDispatcher(provider, 1);
        try {
            assertThatThrownBy(() -> dispatcher.reverseGeocode(Double.NaN, 127))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> dispatcher.reverseGeocode(91, 127))
                    .isInstanceOf(BadRequestException.class);
        } finally { dispatcher.shutdown(); }
    }
}
