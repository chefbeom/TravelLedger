package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RedisStateServiceReconnectTest {
    @Test
    @SuppressWarnings("unchecked")
    void blockedReconnectDoesNotBlockStateRequestsAndHasSingleAttempt() throws Exception {
        RedisClient client = mock(RedisClient.class);
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.connect()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return connection;
        });
        when(connection.isOpen()).thenReturn(true);
        when(connection.sync()).thenReturn(commands);
        when(commands.get("k")).thenReturn("v");
        RedisStateService state = new RedisStateService();
        ReflectionTestUtils.setField(state, "redisClient", client);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            assertThat(state.get("k")).isNull();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            List<Future<Long>> latencies = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                latencies.add(callers.submit(() -> {
                    long startedAt = System.nanoTime();
                    for (int call = 0; call < 20; call++) assertThat(state.get("k")).isNull();
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) / 20;
                }));
            }
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                for (Future<Long> latency : latencies) assertThat(latency.get()).isLessThan(200L);
            });
            verify(client, times(1)).connect();

            release.countDown();
            verify(connection, timeout(2000)).sync();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (state.get("k") == null) Thread.onSpinWait();
            });
            assertThat(state.get("k")).isEqualTo("v");
        } finally {
            release.countDown();
            callers.shutdownNow();
            state.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void commandFailureFallsBackAndSchedulesBackoff() {
        RedisClient client = mock(RedisClient.class);
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(connection.isOpen()).thenReturn(true);
        when(commands.get("k")).thenThrow(new IllegalStateException("offline"));
        RedisStateService state = new RedisStateService();
        ReflectionTestUtils.setField(state, "redisClient", client);
        ReflectionTestUtils.setField(state, "redisConnection", connection);
        ReflectionTestUtils.setField(state, "redisCommands", commands);
        try {
            assertThat(state.get("k")).isNull();
            assertThat(state.get("k")).isNull();
            assertThat(state.increment("counter", Duration.ofMinutes(1))).isEqualTo(-1L);
            assertThat(state.scanKeys("*")).isEmpty();
            verify(commands, times(1)).get("k");
            verify(connection, timeout(2000)).close();
            // Inside the backoff window no reconnect attempt is made.
            verify(client, after(300).never()).connect();
            assertThat((Long) ReflectionTestUtils.getField(state, "nextRedisReconnectAt"))
                    .isGreaterThan(System.currentTimeMillis());
        } finally {
            state.shutdown();
        }
    }
}
