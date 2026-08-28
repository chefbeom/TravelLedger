package com.playdata.calen.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.Test;

class RedisConnectionSupportTest {

    @Test
    void buildsAclUriWithUsernameWithoutConnecting() {
        RedisURI uri = RedisConnectionSupport.buildUri(
                "redis.internal",
                6380,
                4,
                "tenant-user",
                "test-password",
                true
        );

        assertThat(uri.getHost()).isEqualTo("redis.internal");
        assertThat(uri.getPort()).isEqualTo(6380);
        assertThat(uri.getDatabase()).isEqualTo(4);
        assertThat(uri.getUsername()).isEqualTo("tenant-user");
        assertThat(uri.isSsl()).isTrue();
    }

    @Test
    void appliesTenantPrefixToKeysAndScanPatterns() {
        assertThat(RedisConnectionSupport.key("calen-personal:*", "session:1"))
                .isEqualTo("calen-personal:session:1");
        assertThat(RedisConnectionSupport.keys("calen-personal:", "one", "two"))
                .containsExactly("calen-personal:one", "calen-personal:two");
        assertThat(RedisConnectionSupport.pattern("calen-personal:", "*") )
                .isEqualTo("calen-personal:*");
        assertThat(RedisConnectionSupport.stripPrefix("calen-personal:", "calen-personal:auth:lock"))
                .isEqualTo("auth:lock");
    }
}
