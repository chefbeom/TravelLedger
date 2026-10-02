package com.playdata.calen.common.cache;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Shares concurrent cache misses without retaining completed values. */
public final class SingleFlight {
    private final ConcurrentHashMap<String, Flight> flights = new ConcurrentHashMap<>();
    private final Semaphore admission = new Semaphore(128);
    private final ThreadLocal<Flight> current = new ThreadLocal<>();

    @SuppressWarnings("unchecked")
    public <T> T execute(String key, Supplier<T> loader) {
        AtomicBoolean owner = new AtomicBoolean();
        Flight flight = flights.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.invalidated) return existing;
            if (!admission.tryAcquire()) throw new ServiceUnavailableException("요약 조회가 많습니다. 잠시 후 다시 시도해 주세요.");
            owner.set(true);
            return new Flight();
        });
        if (owner.get()) {
            Flight previous = current.get();
            current.set(flight);
            try {
                flight.result.complete(loader.get());
            } catch (Throwable failure) {
                flight.result.completeExceptionally(failure);
            } finally {
                if (previous == null) current.remove(); else current.set(previous);
                flights.remove(key, flight);
                admission.release();
            }
        }
        try {
            return (T) flight.result.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("요약 조회 대기가 중단되었습니다.");
        } catch (TimeoutException failure) {
            throw new ServiceUnavailableException("요약 조회가 지연되고 있습니다. 잠시 후 다시 시도해 주세요.");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(failure.getCause());
        }
    }

    public void publishIfCurrent(String key, Runnable publisher) {
        Flight flight = current.get();
        if (flight == null) return;
        synchronized (flight) {
            if (!flight.invalidated && flights.get(key) == flight) publisher.run();
        }
    }

    public void invalidate(String key) {
        Flight flight = flights.get(key);
        if (flight == null) return;
        synchronized (flight) {
            flight.invalidated = true;
        }
    }

    private static final class Flight {
        private final CompletableFuture<Object> result = new CompletableFuture<>();
        private volatile boolean invalidated;
    }
}
