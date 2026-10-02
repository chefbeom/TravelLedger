package com.playdata.calen.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RedisCacheServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void incompatiblePayloadDoesNotDisableHealthyConnection() {
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(connection.isOpen()).thenReturn(true);
        when(commands.get("legacy")).thenReturn("{malformed JSON");
        when(commands.get("healthy")).thenReturn("\"cached\"");
        RedisCacheService cache = new RedisCacheService(new ObjectMapper());
        ReflectionTestUtils.setField(cache, "redisConnection", connection);
        ReflectionTestUtils.setField(cache, "redisCommands", commands);
        try {
            assertThat(cache.get("legacy", String.class)).isNull();
            assertThat(cache.get("legacy", new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {})).isNull();
            assertThat(cache.get("healthy", String.class)).isEqualTo("cached");
            verify(connection, never()).close();
        } finally {
            cache.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void blockedReconnectDoesNotBlockCacheRequestsAndHasSingleAttempt() throws Exception {
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
        when(commands.get("hello")).thenReturn("\"cached\"");
        RedisCacheService cache = new RedisCacheService(new ObjectMapper());
        ReflectionTestUtils.setField(cache, "redisClient", client);
        try {
            assertThat(cache.get("hello", String.class)).isNull();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 100; i++) assertThat(cache.get("hello", String.class)).isNull();
            });
            verify(client, times(1)).connect();
            release.countDown();
            verify(connection, timeout(2000)).sync();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (cache.get("hello", String.class) == null) Thread.onSpinWait();
            });
            assertThat(cache.get("hello", String.class)).isEqualTo("cached");
        } finally {
            release.countDown();
            cache.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void commandFailureDisablesConnectionAndSubsequentCallsFallBack() {
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(connection.isOpen()).thenReturn(true);
        when(commands.get("hello")).thenThrow(new IllegalStateException("offline"));
        RedisCacheService cache = new RedisCacheService(new ObjectMapper());
        ReflectionTestUtils.setField(cache, "redisConnection", connection);
        ReflectionTestUtils.setField(cache, "redisCommands", commands);
        try {
            assertThat(cache.get("hello", String.class)).isNull();
            assertThat(cache.get("hello", String.class)).isNull();
            verify(commands, times(1)).get("hello");
            verify(connection, timeout(2000)).close();
        } finally {
            cache.shutdown();
        }
    }
}
