package com.playdata.calen.ledger.ai;

import java.util.concurrent.ConcurrentHashMap;
import com.playdata.calen.common.exception.TooManyRequestsException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bounds concurrency and waiting at each upstream model endpoint. Defaults
 * preserve one-at-a-time inference on local GPUs, with bounded admission.
 */
@Component
public class LedgerAiRequestQueue {

    private final ConcurrentHashMap<String, ProviderGate> gates = new ConcurrentHashMap<>();
    private final LedgerAiRequestProperties properties;

    @Autowired(required = false)
    private com.playdata.calen.common.jobs.WorkLeaseService workLeases;

    public LedgerAiRequestQueue() {
        this(new LedgerAiRequestProperties());
    }

    @Autowired
    public LedgerAiRequestQueue(LedgerAiRequestProperties properties) {
        this.properties = properties;
    }

    public <T> T execute(LedgerAiFeatureConfig config, Supplier<T> action) {
        ProviderGate gate = gates.computeIfAbsent(key(config), ignored -> new ProviderGate(
                Math.max(1, properties.getMaxConcurrent()), Math.max(0, properties.getMaxWaiting())));
        if (!gate.admission.tryAcquire()) {
            throw capacityExceeded("AI 서버의 대기 작업이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        boolean acquired = false;
        com.playdata.calen.common.jobs.WorkLeaseService.Lease sharedSlot = null;
        try {
            long timeoutMillis = Math.max(0L, properties.getWaitTimeout().toMillis());
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            acquired = gate.inference.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw capacityExceeded("AI 서버 대기 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("AI request was cancelled.");
            }
            if (workLeases != null) {
                String endpoint = endpointHash(key(config));
                while (sharedSlot == null) {
                    for (int slot = 0; slot < properties.getMaxConcurrent() && sharedSlot == null; slot++) {
                        sharedSlot = workLeases.tryAcquire("ai-provider:" + endpoint + ":" + slot, java.time.Duration.ofMinutes(30));
                    }
                    if (sharedSlot != null) break;
                    if (System.nanoTime() >= deadline) throw capacityExceeded("다른 서버에서 AI 분석 중입니다. 잠시 후 다시 시도해 주세요.");
                    Thread.sleep(Math.min(500, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
                }
            }
            return action.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("AI request was cancelled while waiting.");
        } finally {
            if (sharedSlot != null) sharedSlot.close();
            if (acquired) gate.inference.release();
            gate.admission.release();
        }
    }

    private String endpointHash(String endpoint) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(endpoint.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private TooManyRequestsException capacityExceeded(String message) {
        return new TooManyRequestsException(message, 30);
    }

    private static final class ProviderGate {
        private final Semaphore inference;
        private final Semaphore admission;

        private ProviderGate(int concurrent, int waiting) {
            inference = new Semaphore(concurrent, true);
            admission = new Semaphore(concurrent + waiting, true);
        }
    }

    private String key(LedgerAiFeatureConfig config) {
        if (config == null) {
            return "unconfigured";
        }
        return String.join("|",
                String.valueOf(config.provider()),
                String.valueOf(config.baseUrl()).replaceAll("/+$", ""));
    }
}
