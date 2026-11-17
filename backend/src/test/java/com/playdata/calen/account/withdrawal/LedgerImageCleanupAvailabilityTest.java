package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LedgerImageCleanupAvailabilityTest {
    @Test void cleanupComponentsDoNotExistWithoutTheInternalTestFlag() {
        new ApplicationContextRunner()
                .withUserConfiguration(LedgerImageVersionedObjectCleanup.class, LedgerImageCleanupPreflight.class, LedgerImageCleanupExecutor.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LedgerImageVersionedObjectCleanup.class);
                    assertThat(context).doesNotHaveBean(LedgerImageCleanupPreflight.class);
                    assertThat(context).doesNotHaveBean(LedgerImageCleanupExecutor.class);
                });
    }
}
