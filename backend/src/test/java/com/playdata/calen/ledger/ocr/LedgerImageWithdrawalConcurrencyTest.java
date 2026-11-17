package com.playdata.calen.ledger.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerImageInputReferenceAdapter;
import com.playdata.calen.ledger.application.port.out.LedgerImageStoragePort;
import com.playdata.calen.ledger.application.service.StoreLedgerImageInputService;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus;
import com.playdata.calen.ledger.domain.model.LedgerImageStorageData.StoredImage;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:image-withdrawal-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@Import({LedgerImageAnalysisHistoryWriter.class, JpaLedgerImageInputReferenceAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerImageWithdrawalConcurrencyTest {
    @Autowired AppUserRepository users;
    @Autowired LedgerImageAnalysisRequestRepository images;
    @Autowired LedgerImageAnalysisHistoryWriter writer;
    @Autowired JpaLedgerImageInputReferenceAdapter references;
    @Autowired PlatformTransactionManager transactions;

    @Test void newImageHistoryWaitsForDeactivationAndIsRejected() throws Exception {
        Long owner = user();
        withDeactivationHeld(owner, () -> writer.create(owner, new LedgerImageAnalysisRequest()), NotFoundException.class);
        assertThat(images.findAllByOwnerIdOrderByCreatedAtDescIdDesc(owner, org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
    }

    @Test void imageRetryWaitsForDeactivationAndDoesNotRestart() throws Exception {
        Long owner = user();
        var draft = new LedgerImageAnalysisRequest();
        draft.setStatus(LedgerImageAnalysisStatus.FAILED);
        var history = writer.create(owner, draft);
        withDeactivationHeld(owner, () -> writer.restart(owner, history.getId(), history.getVersion(), "AUTO", "retry", LocalDateTime.now()),
                NotFoundException.class);
        var unchanged = images.findById(history.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(LedgerImageAnalysisStatus.FAILED);
        assertThat(unchanged.getVersion()).isEqualTo(history.getVersion());
    }

    @Test void uploadWaitingOnDeactivationDoesNotAttachAndCompensatesItsNewObject() throws Exception {
        Long owner = user();
        var history = writer.create(owner, new LedgerImageAnalysisRequest());
        var storage = mock(LedgerImageStoragePort.class);
        var upload = mock(LedgerImageStoragePort.Upload.class);
        when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(any(), any(), any())).thenReturn(new StoredImage("new-image-key", LocalDateTime.now()));
        var input = new StoreLedgerImageInputService(storage, references);
        withDeactivationHeld(owner, () -> input.store(owner, history.getId(), upload), NotFoundException.class);
        assertThat(images.findById(history.getId()).orElseThrow().getImageObjectKey()).isNull();
        verify(storage).delete("new-image-key");
    }

    @Test void attachingStoredImagePersistsBucketAndLegacyReplacementClearsOldProvenance() {
        Long owner = user();
        var history = writer.create(owner, new LedgerImageAnalysisRequest());
        assertThat(references.attach(owner, history.getId(), new StoredImage("first-key", LocalDateTime.now(), "old-bucket",
                "fixture_storage_id_1234", "fixture-version", "fixture-etag"))).isEqualTo(1);
        var stored = images.findById(history.getId()).orElseThrow();
        assertThat(stored.getImageObjectKey()).isEqualTo("first-key");
        assertThat(stored.getImageStorageBucket()).isEqualTo("old-bucket");
        assertThat(stored.getImageStorageId()).isEqualTo("fixture_storage_id_1234");
        assertThat(stored.getImageObjectVersion()).isEqualTo("fixture-version");
        assertThat(stored.getImageObjectEtag()).isEqualTo("fixture-etag");
        assertThat(references.attach(owner, history.getId(), new StoredImage("second-key", LocalDateTime.now(), "old-bucket",
                "fixture_storage_id_1234", "other-version"))).isEqualTo(1);
        var withoutEtag = images.findById(history.getId()).orElseThrow();
        assertThat(withoutEtag.getImageObjectKey()).isEqualTo("second-key");
        assertThat(withoutEtag.getImageObjectVersion()).isEqualTo("other-version");
        assertThat(withoutEtag.getImageObjectEtag()).isNull();
        assertThat(references.attach(owner, history.getId(), new StoredImage("replacement-key", LocalDateTime.now()))).isEqualTo(1);
        var legacy = images.findById(history.getId()).orElseThrow();
        assertThat(legacy.getImageObjectKey()).isEqualTo("replacement-key");
        assertThat(legacy.getImageStorageBucket()).isNull();
        assertThat(legacy.getImageStorageId()).isNull();
        assertThat(legacy.getImageObjectVersion()).isNull();
        assertThat(legacy.getImageObjectEtag()).isNull();
    }

    private void withDeactivationHeld(Long ownerId, java.util.concurrent.Callable<?> operation,
            Class<? extends Throwable> expected) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var owner = users.lockForAccountSecurityUpdate(ownerId).orElseThrow();
                owner.setActive(false);
                users.saveAndFlush(owner);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var attempt = pool.submit(operation);
            assertThatThrownBy(() -> attempt.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> attempt.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(expected);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private Long user() {
        var user = new AppUser();
        user.setLoginId("image_" + UUID.randomUUID());
        user.setDisplayName("이미지 검증 사용자");
        return users.saveAndFlush(user).getId();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test lock timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
