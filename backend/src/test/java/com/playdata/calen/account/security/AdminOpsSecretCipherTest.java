package com.playdata.calen.account.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminOpsSecretCipherTest {

    @Test
    void encryptsCredentialsWithoutPersistingPlaintext() {
        AdminOpsSecretCipher cipher = new AdminOpsSecretCipher("test-admin-ops-seal");

        String encrypted = cipher.encrypt("sk-sensitive-value");

        assertThat(encrypted).doesNotContain("sk-sensitive-value");
        assertThat(cipher.decrypt(encrypted)).contains("sk-sensitive-value");
    }

    @Test
    void decryptsCiphertextCreatedWithTheLegacyJwtSealAfterDedicatedSealIsConfigured() {
        AdminOpsSecretCipher legacy = new AdminOpsSecretCipher("", "legacy-jwt-key", true);
        AdminOpsSecretCipher current = new AdminOpsSecretCipher("new-ops-seal-key", "legacy-jwt-key", true);

        String encrypted = legacy.encrypt("sk-existing-value");

        assertThat(current.decrypt(encrypted)).contains("sk-existing-value");
    }
    @Test
    void rejectsCiphertextCreatedWithAnotherSeal() {
        AdminOpsSecretCipher source = new AdminOpsSecretCipher("first-seal");
        AdminOpsSecretCipher other = new AdminOpsSecretCipher("second-seal");

        assertThat(other.decrypt(source.encrypt("secret"))).isEmpty();
    }
}