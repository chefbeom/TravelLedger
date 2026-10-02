package com.playdata.calen.ledger.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.playdata.calen.ledger.domain.LedgerExcelAnalysisJob;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.schema.legacy-updaters.enabled=false"})
class LedgerExcelAnalysisJobRepositoryTest {
    @Autowired private LedgerExcelAnalysisJobRepository repository;
    @Autowired private TestEntityManager entityManager;

    @Test
    void recoveryCursorHandlesEqualCreationTimesWithoutSkippingOrRepeatingRows() {
        LocalDateTime timestamp = LocalDateTime.now().minusMinutes(5);
        save("001", timestamp, "PROCESSING"); save("002", timestamp, "PROCESSING");
        save("003", timestamp.plusSeconds(1), "PROCESSING"); save("004", timestamp, "COMPLETED");
        // Recovery has a fresh persistence context and uses the timestamp precision stored by the database.
        entityManager.clear();
        LocalDateTime before = LocalDateTime.now().minusMinutes(1);

        var first = repository.findRecoveryPage("PROCESSING", before, null, "", PageRequest.of(0, 1));
        assertThat(first).extracting(LedgerExcelAnalysisJob::getId).containsExactly("001");
        assertThat(repository.findRecoveryPage("PROCESSING", before, first.get(0).getCreatedAt(),
                first.get(0).getId(), PageRequest.of(0, 100)))
                .extracting(LedgerExcelAnalysisJob::getId).containsExactly("002", "003");
    }

    private void save(String id, LocalDateTime createdAt, String status) {
        var job = new LedgerExcelAnalysisJob();
        job.setId(id); job.setOwnerId(7L); job.setCreatedAt(createdAt); job.setStatus(status);
        repository.saveAndFlush(job);
    }
}
