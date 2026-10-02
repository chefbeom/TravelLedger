package com.playdata.calen.ledger.repository;

import static org.assertj.core.api.Assertions.*;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.schema.legacy-updaters.enabled=false"})
class LedgerImageJobRepositoryTest {
    @Autowired private TestEntityManager entityManager;
    @Autowired private LedgerImageAnalysisRequestRepository repository;

    @Test
    void clientRequestIdIsUniquePerOwner() {
        AppUser owner = owner();
        repository.saveAndFlush(job(owner));
        assertThatThrownBy(() -> repository.saveAndFlush(job(owner))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleCompletionCannotOverwriteCancellation() {
        var original = repository.saveAndFlush(job(owner()));
        entityManager.detach(original);
        var current = repository.findById(original.getId()).orElseThrow();
        current.setStatus(LedgerImageAnalysisStatus.CANCELLED);
        repository.saveAndFlush(current);
        original.setStatus(LedgerImageAnalysisStatus.COMPLETED);
        assertThatThrownBy(() -> repository.saveAndFlush(original)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    private AppUser owner() {
        AppUser owner = new AppUser(); owner.setLoginId("owner"); owner.setDisplayName("owner"); owner.setPasswordHash("test-only-hash");
        return entityManager.persistAndFlush(owner);
    }
    private LedgerImageAnalysisRequest job(AppUser owner) {
        var job = new LedgerImageAnalysisRequest(); job.setOwner(owner); job.setClientRequestId("request-1"); return job;
    }
}
