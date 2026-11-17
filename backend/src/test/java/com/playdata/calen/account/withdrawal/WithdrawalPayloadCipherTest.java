package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WithdrawalPayloadCipherTest {
    private final UUID request = UUID.randomUUID();
    private final UUID task = UUID.randomUUID();
    private final WithdrawalPayloadCipher cipher = new WithdrawalPayloadCipher("test-withdrawal-secret-at-least-32-characters");

    @Test
    void encryptsTargetsWithUniqueNoncesAndBindsThemToTheTaskContext() {
        String target = "family-media/7/12/private-photo.jpg";
        String first = cipher.encrypt(request, task, "OBJECT", target);
        String second = cipher.encrypt(request, task, "OBJECT", target);
        assertThat(first).doesNotContain(target).isNotEqualTo(second);
        assertThat(cipher.decrypt(request, task, "OBJECT", first)).isEqualTo(target);
        assertThatThrownBy(() -> cipher.decrypt(request, UUID.randomUUID(), "OBJECT", first))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining(target);
        assertThatThrownBy(() -> cipher.decrypt(request, task, "KAKAO", first))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> cipher.decrypt(request, task, "OBJECT", first + "x"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void neverUsesAnEphemeralOrPlaceholderKeyForDurableTasks() {
        for (String value : new String[]{"", "short", "change-me-withdrawal-key-with-32-chars"}) {
            WithdrawalPayloadCipher unconfigured = new WithdrawalPayloadCipher(value);
            assertThat(unconfigured.isConfigured()).isFalse();
            assertThatThrownBy(() -> unconfigured.encrypt(request, task, "OBJECT", "private"))
                    .isInstanceOf(ServiceUnavailableException.class);
        }
    }

    @Test
    void fingerprintsAreStableOnlyForTheSameRequestAndPurpose() {
        String fingerprint = cipher.fingerprint(request, "TASK_KEY", "media:123");
        assertThat(fingerprint).isEqualTo(cipher.fingerprint(request, "TASK_KEY", "media:123"));
        assertThat(fingerprint).doesNotContain("media:123")
                .isNotEqualTo(cipher.fingerprint(request, "PAYLOAD", "media:123"))
                .isNotEqualTo(cipher.fingerprint(UUID.randomUUID(), "TASK_KEY", "media:123"));
    }
}
