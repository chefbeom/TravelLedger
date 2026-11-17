package com.playdata.calen.account.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class KakaoPendingRegistrationTest {
    @Test void javaSerializedSessionRetainsVerifiedAppAndLegacyConstructorHasNoOrigin() throws Exception {
        Instant expiry = Instant.parse("2026-11-03T12:00:00Z");
        var pending = new KakaoPendingRegistration("12345", "user@example.test", "user", expiry, 1234L);
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) { out.writeObject(pending); }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(in.readObject()).isEqualTo(pending);
        }
        assertThat(new KakaoPendingRegistration("12345", "user@example.test", "user", expiry).providerAppId()).isNull();
    }
}
