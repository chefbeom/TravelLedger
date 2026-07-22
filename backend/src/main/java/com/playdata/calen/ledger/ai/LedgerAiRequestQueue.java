package com.playdata.calen.ledger.ai;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Serializes requests sent to the same upstream model endpoint.  Local model
 * servers frequently accept a new request while still generating the prior
 * one; a fair lock prevents one ledger feature from interrupting another.
 */
@Component
public class LedgerAiRequestQueue {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T execute(LedgerAiFeatureConfig config, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(key(config), ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private String key(LedgerAiFeatureConfig config) {
        if (config == null) {
            return "unconfigured";
        }
        return String.join("|",
                String.valueOf(config.provider()),
                String.valueOf(config.baseUrl()));
    }
}
