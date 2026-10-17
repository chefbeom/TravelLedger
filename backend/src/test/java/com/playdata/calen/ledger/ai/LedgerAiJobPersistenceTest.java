package com.playdata.calen.ledger.ai;

import com.playdata.calen.ledger.application.port.in.StoreLedgerImageInputUseCase;
import com.playdata.calen.ledger.domain.model.LedgerImageStorageData;

import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Result;
import com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisRequest;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisResponse;
import com.playdata.calen.ledger.dto.OverviewResponse;
import com.playdata.calen.ledger.application.port.out.LedgerImageStoragePort;
import com.playdata.calen.ledger.ocr.LedgerOcrProperties;
import com.playdata.calen.ledger.application.port.out.LedgerImageExtractionPort;
import com.playdata.calen.ledger.domain.model.LedgerImageData;
import com.playdata.calen.ledger.ocr.LedgerOcrService;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository;
import com.playdata.calen.ledger.service.StatisticsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;
import com.playdata.calen.ledger.domain.LedgerExcelAnalysisJob;
import com.playdata.calen.ledger.service.LedgerAiExcelJobService;
import com.playdata.calen.ledger.service.LedgerAiExcelImportService;
import com.playdata.calen.ledger.dto.LedgerExcelPreviewResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Each repository call commits separately, just as it does in the asynchronous workers. */
@DataJpaTest(showSql = false, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false", "app.schema.legacy-updaters.enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerAiJobPersistenceTest {

    @Autowired private AppUserRepository users;
    @Autowired private LedgerImageAnalysisRequestRepository images;
    @Autowired private LedgerAiAnalysisHistoryRepository histories;
    @Autowired private LedgerEntryRepository entries;
    @Autowired private com.playdata.calen.ledger.repository.LedgerExcelAnalysisJobRepository excel;
    @Autowired private CategoryGroupRepository groups;
    @Autowired private CategoryDetailRepository details;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    private AppUser owner;
    private LedgerOcrService imageService;
    private LedgerAiAnalysisService analysisService;
    private LedgerImageExtractionPort imageRemote;
    private LedgerSpendingAnalysisPort analysisRemote;
    private CapturingImageExecutor imageExecutor;
    private List<Runnable> analysisTasks;
    private com.playdata.calen.ledger.application.port.in.FinalizeLedgerAiJobUseCase finalizer;

    @BeforeEach
    void setUp() {
        owner = new AppUser();
        owner.setLoginId("ai-fixture-" + UUID.randomUUID());
        owner.setDisplayName("AI regression fixture");
        owner.setPasswordHash("test-only-hash");
        owner = users.saveAndFlush(owner);
        AppUserService userService = mock(AppUserService.class);
        when(userService.getRequiredUser(owner.getId())).thenReturn(owner);

        var properties = new LedgerAiAnalysisProperties();
        properties.setEnabled(true);
        properties.setProvider("lmstudio");
        properties.setLmStudioBaseUrl("http://localhost:1234");
        properties.setModel("test-model");
        var mapper = new ObjectMapper().findAndRegisterModules();
        finalizer = new com.playdata.calen.ledger.application.service.FinalizeLedgerAiJobService(
                new com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerAiJobStateAdapter(histories, images, excel));
        imageRemote = mock(LedgerImageExtractionPort.class);
        when(imageRemote.analyze(any(), anyString(), any())).thenReturn(
                new LedgerImageData.ImageExtraction(true, null, "AUTO", "fixture receipt",
                        null, List.of(), Map.of()));
        imageService = new LedgerOcrService(userService, new LedgerOcrProperties(), properties, imageRemote,
                images, groups, details, entries, mapper, finalizer, mock(StoreLedgerImageInputUseCase.class));
        imageExecutor = new CapturingImageExecutor();
        ReflectionTestUtils.setField(imageService, "ledgerOcrTaskExecutor", imageExecutor);

        var statistics = mock(StatisticsService.class);
        when(statistics.getOverview(any(), any(), any())).thenReturn(new OverviewResponse(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                BigDecimal.ZERO, new BigDecimal("15000"), new BigDecimal("-15000"), 1));
        analysisRemote = mock(LedgerSpendingAnalysisPort.class);
        when(analysisRemote.analyze(any())).thenReturn(remoteAnalysis());
        var text = new LedgerAiAnalysisTextSanitizer();
        var codec = new LedgerAiAnalysisJsonCodec(mapper);
        var metrics = new LedgerAiAnalysisMetrics(properties, new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        var datasets = new com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisDatasetPreparation(
                new com.playdata.calen.ledger.adapter.out.persistence.LedgerAnalysisDatasetQueryAdapter(statistics, entries));
        var formats = new com.playdata.calen.ledger.adapter.out.analysis.LedgerAnalysisFormats(properties,
                new LedgerAiAnalysisPayloadBuilder(text), codec, text, new LedgerAiAnalysisReportMerger(text));
        var worker = new com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisJobExecution(datasets,
                new com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerAnalysisWorkStore(histories), formats,
                analysisRemote, finalizer, new com.playdata.calen.ledger.adapter.out.observability.LedgerAnalysisWorkTelemetry(metrics));
        analysisService = new LedgerAiAnalysisService(userService,
                datasets, histories,
                new com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisHistories(
                        new com.playdata.calen.ledger.adapter.out.persistence.LedgerAnalysisHistoryQueryAdapter(userService, histories),
                        new com.playdata.calen.ledger.adapter.out.serialization.LedgerAnalysisHistoryJsonAdapter(codec)), properties,
                new LedgerAiAnalysisStatusService(properties), analysisRemote,
                metrics, codec, text, finalizer, formats, worker);
        analysisTasks = new ArrayList<>();
        ReflectionTestUtils.setField(analysisService, "ledgerAiTaskExecutor", (TaskExecutor) analysisTasks::add);
    }

    @AfterEach
    void releaseQueuedTasks() {
        imageExecutor.tasks.forEach(task -> ((Future<?>) task).cancel(true));
        analysisTasks.forEach(task -> ((Future<?>) task).cancel(true));
    }

    @Test
    void storedImageCanBeAcceptedAndCompletedAcrossRepositoryTransactions() throws Exception {
        var storage = mock(LedgerImageStoragePort.class);
        when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(any(), any(), any())).thenReturn(
                new LedgerImageStorageData.StoredImage("fixture/image", LocalDateTime.now()));
        when(storage.load(anyString(), any(), any())).thenReturn(
                new LedgerImageStorageData.StoredImageContent(jpegBytes(), "image/jpeg", "receipt.jpg"));
        installStorage(storage);

        var accepted = imageService.startAnalyze(owner.getId(), image(), "AUTO", "stored", "fixture prompt", false);
        assertThat(accepted.analysisStatus()).isEqualTo("PROCESSING");
        var stored = images.findById(accepted.analysisId()).orElseThrow();
        assertThat(stored.getEffectivePrompt()).contains("fixture prompt");
        assertThat(stored.getImageObjectKey()).isEqualTo("fixture/image");

        runWorker(imageExecutor.tasks.get(0));
        var completed = imageService.getHistory(owner.getId(), accepted.analysisId());
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.result().rawText()).isEqualTo("fixture receipt");
        verify(imageRemote).analyze(any(), anyString(), any());
    }

    @Test
    void temporaryImageAlsoCompletesAcrossRepositoryTransactions() throws Exception {
        var accepted = imageService.startAnalyze(owner.getId(), image(), "AUTO", "temporary", "", false);
        runWorker(imageExecutor.tasks.get(0));
        var completed = imageService.getHistory(owner.getId(), accepted.analysisId());
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.result().rawText()).isEqualTo("fixture receipt");
    }

    @Test
    void completedLlmResponseIsPersistedAndVisibleToSubsequentPolling() throws Exception {
        var accepted = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        assertThat(accepted.history().status()).isEqualTo(LedgerAiAnalysisStatus.PROCESSING);
        runWorker(analysisTasks.get(0));
        var completed = analysisService.getHistory(owner.getId(), accepted.history().id());
        assertThat(completed.history().status()).isEqualTo(LedgerAiAnalysisStatus.COMPLETED);
        assertThat(completed.result().summary()).isEqualTo("fixture spending summary");
        assertThat(completed.result().recommendations()).contains("Review dining expenses");
        verify(analysisRemote).analyze(any());
    }

    @Test
    void providerFailureBecomesFailedInsteadOfRemainingProcessing() throws Exception {
        when(analysisRemote.analyze(any())).thenThrow(new BadRequestException("fixture provider unavailable"));
        var accepted = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        runWorker(analysisTasks.get(0));
        var failed = analysisService.getHistory(owner.getId(), accepted.history().id());
        assertThat(failed.history().status()).isEqualTo(LedgerAiAnalysisStatus.FAILED);
        assertThat(failed.history().errorMessage()).contains("fixture provider unavailable");
    }

    @Test
    void imageProviderFailureIsVisibleToSubsequentPolling() throws Exception {
        when(imageRemote.analyze(any(), anyString(), any())).thenThrow(new BadRequestException("fixture image unavailable"));
        var accepted = imageService.startAnalyze(owner.getId(), image(), "AUTO", "failure", "", false);
        runWorker(imageExecutor.tasks.get(0));
        var failed = imageService.getHistory(owner.getId(), accepted.analysisId());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorMessage()).contains("fixture image unavailable");
    }

    @Test
    void cancellingAQueuedImageIsPersistedBeforeTheWorkerIsInterrupted() {
        var accepted = imageService.startAnalyze(owner.getId(), image(), "AUTO", "cancel", "", false);
        var cancelled = imageService.cancelHistory(owner.getId(), accepted.analysisId());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(((Future<?>) imageExecutor.tasks.get(0)).isCancelled()).isTrue();
        assertThat(imageService.getHistory(owner.getId(), accepted.analysisId()).status()).isEqualTo("CANCELLED");
        verify(imageRemote, never()).analyze(any(), anyString(), any());
    }

    @Test
    void imageCompletionCannotOverwriteCancellationFromAnotherServer() throws Exception {
        var accepted = imageService.startAnalyze(owner.getId(), image(), "AUTO", "cancel-during-inference", "", false);
        when(imageRemote.analyze(any(), anyString(), any())).thenAnswer(invocation -> {
            assertThat(images.cancelProcessing(accepted.analysisId(), owner.getId(), "cancelled elsewhere", LocalDateTime.now()))
                    .isEqualTo(1);
            return new LedgerImageData.ImageExtraction(true, null, "AUTO", "late response", null, List.of(), Map.of());
        });
        runWorker(imageExecutor.tasks.get(0));
        var cancelled = imageService.getHistory(owner.getId(), accepted.analysisId());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.result()).isNull();
        assertThat(images.failProcessing(accepted.analysisId(), owner.getId(), "late failure", "failed", LocalDateTime.now()))
                .isZero();
    }

    @Test
    void cancellationDuringOriginalImageUploadDoesNotCauseAnOptimisticLockFailure() {
        var storage = mock(LedgerImageStoragePort.class);
        when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(any(), any(), any())).thenAnswer(invocation -> {
            imageService.cancelHistory(owner.getId(), invocation.getArgument(1));
            return new LedgerImageStorageData.StoredImage("fixture/cancelled-image", LocalDateTime.now());
        });
        installStorage(storage);

        var cancelled = imageService.startAnalyze(owner.getId(), image(), "AUTO", "cancel-upload", "", false);
        assertThat(cancelled.analysisStatus()).isEqualTo("CANCELLED");
        var stored = imageService.getHistory(owner.getId(), cancelled.analysisId());
        assertThat(stored.status()).isEqualTo("CANCELLED");
        assertThat(stored.imageAvailable()).isTrue();
        assertThat(imageExecutor.tasks).isEmpty();
        verify(imageRemote, never()).analyze(any(), anyString(), any());
    }

    @Test
    void deletionDuringOriginalImageUploadCleansTheUnattachedObjectWithoutRecreatingTheHistory() {
        var storage = mock(LedgerImageStoragePort.class);
        when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(any(), any(), any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(1);
            images.cancelProcessing(id, owner.getId(), "cancelled elsewhere", LocalDateTime.now());
            images.deleteById(id);
            return new LedgerImageStorageData.StoredImage("fixture/deleted-image", LocalDateTime.now());
        });
        installStorage(storage);

        assertThatThrownBy(() -> imageService.startAnalyze(owner.getId(), image(), "AUTO", "delete-upload", "", false))
                .isInstanceOf(NotFoundException.class);
        assertThat(images.findByClientRequestIdAndOwnerId("delete-upload", owner.getId())).isEmpty();
        assertThat(imageExecutor.tasks).isEmpty();
        verify(storage).delete("fixture/deleted-image");
        verify(imageRemote, never()).analyze(any(), anyString(), any());
    }

    @Test
    void storedImageCanRecoverAfterRestartUsingThePersistedInputReference() throws Exception {
        var storage = mock(LedgerImageStoragePort.class);
        when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(any(), any(), any())).thenReturn(
                new LedgerImageStorageData.StoredImage("fixture/recovered-image", LocalDateTime.now()));
        when(storage.load(anyString(), any(), any())).thenReturn(
                new LedgerImageStorageData.StoredImageContent(jpegBytes(), "image/jpeg", "receipt.jpg"));
        installStorage(storage);
        var accepted = imageService.startAnalyze(owner.getId(), image(), "AUTO", "recover-image", "fixture prompt", false);
        ((Future<?>) imageExecutor.tasks.get(0)).cancel(false);
        var pending = images.findById(accepted.analysisId()).orElseThrow();
        pending.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        images.saveAndFlush(pending);
        ReflectionTestUtils.setField(imageService, "workLeases", new WorkLeaseService(dataSource, transactionManager));

        readOnlyScan(() -> ReflectionTestUtils.invokeMethod(imageService, "recoverImageJobs"));
        assertThat(imageExecutor.tasks).hasSize(2);
        runWorker(imageExecutor.tasks.get(1));
        assertThat(imageService.getHistory(owner.getId(), accepted.analysisId()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void unrecoverableImageIsFinalizedOutsideTheReadOnlyScanWithoutCallingTheProvider() {
        var accepted = imageService.startAnalyze(owner.getId(), image(), "AUTO", "legacy-image", "", false);
        ((Future<?>) imageExecutor.tasks.get(0)).cancel(false);
        var pending = images.findById(accepted.analysisId()).orElseThrow();
        pending.setCreatedAt(LocalDateTime.now().minusMinutes(2)); pending.setEffectivePrompt(null);
        pending = images.saveAndFlush(pending);
        long version = pending.getVersion(); long ledgerCount = entries.count();
        ReflectionTestUtils.setField(imageService, "workLeases", new WorkLeaseService(dataSource, transactionManager));

        readOnlyScan(() -> ReflectionTestUtils.invokeMethod(imageService, "recoverImageJobs"));

        var failed = images.findById(accepted.analysisId()).orElseThrow();
        assertThat(failed.getStatus().name()).isEqualTo("FAILED"); assertThat(failed.getErrorMessage()).contains("이미지를 다시 분석");
        assertThat(failed.getVersion()).isEqualTo(version + 1); assertThat(imageExecutor.tasks).hasSize(1);
        assertThat(entries.count()).isEqualTo(ledgerCount); verify(imageRemote, never()).analyze(any(), anyString(), any());
    }

    @Test
    void unrecoverableImageStillOwnedByAnotherWorkerRemainsProcessingUntilItsLeaseIsReleased() {
        var accepted = imageService.startAnalyze(owner.getId(), image(), "AUTO", "leased-legacy-image", "", false);
        ((Future<?>) imageExecutor.tasks.get(0)).cancel(false);
        var pending = images.findById(accepted.analysisId()).orElseThrow();
        pending.setCreatedAt(LocalDateTime.now().minusMinutes(2)); pending.setEffectivePrompt(null);
        pending = images.saveAndFlush(pending); long version = pending.getVersion();
        var leases = new WorkLeaseService(dataSource, transactionManager);
        ReflectionTestUtils.setField(imageService, "workLeases", leases);
        try (var held = leases.tryAcquire("image-job:" + accepted.analysisId(), Duration.ofMinutes(30))) {
            assertThat(held).isNotNull();
            readOnlyScan(() -> ReflectionTestUtils.invokeMethod(imageService, "recoverImageJobs"));
            var unchanged = images.findById(accepted.analysisId()).orElseThrow();
            assertThat(unchanged.getStatus().name()).isEqualTo("PROCESSING"); assertThat(unchanged.getVersion()).isEqualTo(version);
        }
        readOnlyScan(() -> ReflectionTestUtils.invokeMethod(imageService, "recoverImageJobs"));
        assertThat(images.findById(accepted.analysisId()).orElseThrow().getStatus().name()).isEqualTo("FAILED");
        verify(imageRemote, never()).analyze(any(), anyString(), any());
    }

    @Test
    void deletedImageIsNotRecreatedByALateCompletion() throws Exception {
        var accepted = imageService.startAnalyze(owner.getId(), image(), "AUTO", "deleted", "", false);
        when(imageRemote.analyze(any(), anyString(), any())).thenAnswer(invocation -> {
            images.deleteById(accepted.analysisId());
            return new LedgerImageData.ImageExtraction(true, null, "AUTO", "late response", null, List.of(), Map.of());
        });
        runWorker(imageExecutor.tasks.get(0));
        assertThat(images.findById(accepted.analysisId())).isEmpty();
    }

    @Test
    void deletingAnAnalysisDuringInferenceDoesNotLeaveAnUncaughtSaveFailure() throws Exception {
        var accepted = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        when(analysisRemote.analyze(any())).thenAnswer(invocation -> {
            histories.deleteById(accepted.history().id());
            return remoteAnalysis();
        });
        runWorker(analysisTasks.get(0));
        assertThat(histories.findById(accepted.history().id())).isEmpty();
    }

    @Test
    void lateAnalysisFailureCannotOverwriteAnAlreadyCompletedResult() throws Exception {
        var accepted = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        when(analysisRemote.analyze(any())).thenAnswer(invocation -> {
            histories.completeProcessing(accepted.history().id(), owner.getId(), "completed elsewhere", "{}");
            throw new BadRequestException("late provider failure");
        });
        runWorker(analysisTasks.get(0));
        var completed = histories.findById(accepted.history().id()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(LedgerAiAnalysisStatus.COMPLETED);
        assertThat(completed.getSummary()).isEqualTo("completed elsewhere");
        assertThat(completed.getErrorMessage()).isNull();
    }

    @Test
    void busyDurableAdmissionReusesTheCommittedProcessingRowWithoutCreatingAnotherTask() {
        var leases = spy(new WorkLeaseService(dataSource, transactionManager));
        ReflectionTestUtils.setField(analysisService, "workLeases", leases);
        var first = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        String admissionKey = org.mockito.Mockito.mockingDetails(leases).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("tryAcquire") && ((String) call.getArgument(0)).startsWith("analysis-admission:"))
                .map(call -> (String) call.getArgument(0)).findFirst().orElseThrow();
        var otherNode = new WorkLeaseService(dataSource, transactionManager);
        try (var held = otherNode.tryAcquire(admissionKey, Duration.ofMinutes(2))) {
            assertThat(held).isNotNull();
            var reused = analysisService.startAnalyze(owner.getId(), monthlyRequest());
            assertThat(reused.history().id()).isEqualTo(first.history().id()); assertThat(reused.result()).isNull();
        }
        assertThat(histories.countByOwnerId(owner.getId())).isEqualTo(1); assertThat(analysisTasks).hasSize(1);
        assertThat(histories.findById(first.history().id()).orElseThrow().getStatus()).isEqualTo(LedgerAiAnalysisStatus.PROCESSING);
        verify(analysisRemote, never()).analyze(any());
    }

    @Test
    void rejectedAsyncSubmissionFailsOnlyTheSavedRowAndReleasesAllReservationsAndLeases() {
        var leases = spy(new WorkLeaseService(dataSource, transactionManager));
        ReflectionTestUtils.setField(analysisService, "workLeases", leases);
        ReflectionTestUtils.setField(analysisService, "ledgerAiTaskExecutor", (TaskExecutor) task -> { throw new org.springframework.core.task.TaskRejectedException("fixture queue full"); });
        assertThatThrownBy(() -> analysisService.startAnalyze(owner.getId(), monthlyRequest()))
                .isInstanceOf(com.playdata.calen.common.exception.TooManyRequestsException.class).hasMessageContaining("대기열");
        var saved = histories.findAllByOwnerIdOrderByCreatedAtDescIdDesc(owner.getId(), org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(saved.getContent()).hasSize(1); var row = saved.getContent().get(0);
        assertThat(row.getStatus()).isEqualTo(LedgerAiAnalysisStatus.FAILED); assertThat(row.getVersion()).isEqualTo(1);
        assertThat(row.getResultJson()).isNull();
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(analysisService, "analysisTasks")).isEmpty();
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(analysisService, "pendingAnalyses")).isEmpty();
        var keys = org.mockito.Mockito.mockingDetails(leases).getInvocations().stream().filter(call -> call.getMethod().getName().equals("tryAcquire"))
                .map(call -> (String) call.getArgument(0)).distinct().toList();
        assertThat(keys).hasSize(2);
        var otherNode = new WorkLeaseService(dataSource, transactionManager);
        for (String key : keys) try (var acquired = otherNode.tryAcquire(key, Duration.ofMinutes(2))) { assertThat(acquired).isNotNull(); }
        verify(analysisRemote, never()).analyze(any());
    }

    @Test
    void asyncAdmissionSuspendsTheCallerTransactionAndCommitsProcessingBeforeOuterRollback() {
        ReflectionTestUtils.setField(analysisService, "workLeases", new WorkLeaseService(dataSource, transactionManager));
        ReflectionTestUtils.setField(analysisService, "ledgerAiTaskExecutor", (TaskExecutor) task -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); analysisTasks.add(task);
        });
        var proxy = transactionalAnalysis();
        var accepted = new TransactionTemplate(transactionManager).execute(tx -> {
            var response = proxy.startAnalyze(owner.getId(), monthlyRequest());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); tx.setRollbackOnly(); return response;
        });
        assertThat(histories.findById(accepted.history().id()).orElseThrow().getStatus()).isEqualTo(LedgerAiAnalysisStatus.PROCESSING);
        assertThat(analysisTasks).hasSize(1); verify(analysisRemote, never()).analyze(any());
    }

    @Test
    void directWorkerUseCaseSuspendsTheCallerTransactionAndCompletesBeforeOuterRollback() {
        var accepted = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        var row = histories.findById(accepted.history().id()).orElseThrow();
        var execution = (com.playdata.calen.ledger.application.port.in.ExecuteLedgerAnalysisUseCase) ReflectionTestUtils.getField(analysisService, "backgroundAnalysis");
        var factory = new ProxyFactory(execution); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        var proxy = (com.playdata.calen.ledger.application.port.in.ExecuteLedgerAnalysisUseCase) factory.getProxy();
        when(analysisRemote.analyze(any())).thenAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return remoteAnalysis(); });
        var plan = workerPlan(row);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            proxy.process(owner.getId(), row.getId(), plan, () -> true);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); tx.setRollbackOnly();
        });
        var result = histories.findById(row.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(LedgerAiAnalysisStatus.COMPLETED); assertThat(result.getResultJson()).isNotBlank();
    }

    @Test
    void directWorkerCannotLoadOrFinalizeAnotherOwnersJobAndSkipsLeaseAndProvider() {
        var accepted = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        var row = histories.findById(accepted.history().id()).orElseThrow(); long version = row.getVersion();
        var execution = (com.playdata.calen.ledger.application.port.in.ExecuteLedgerAnalysisUseCase) ReflectionTestUtils.getField(analysisService, "backgroundAnalysis");
        execution.process(owner.getId() + 100000, row.getId(), workerPlan(row), () -> { throw new AssertionError("foreign job must stop before lease checks"); });
        var unchanged = histories.findById(row.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(LedgerAiAnalysisStatus.PROCESSING); assertThat(unchanged.getVersion()).isEqualTo(version);
        assertThat(unchanged.getRequestPayloadJson()).isNull(); assertThat(unchanged.getResultJson()).isNull(); verify(analysisRemote, never()).analyze(any());
    }

    private com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan workerPlan(LedgerAiAnalysisHistory row) {
        return new com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan(row.getMode(), row.getPeriodType(), row.getComparisonPreset(),
                com.playdata.calen.ledger.adapter.support.LedgerAnalysisPreparationMapper.range(row.getFromDate(), row.getToDate()), row.getCompareFromDate() == null ? null
                : com.playdata.calen.ledger.adapter.support.LedgerAnalysisPreparationMapper.range(row.getCompareFromDate(), row.getCompareToDate()), row.getFocusPrompt());
    }

    @Test
    void interruptedAnalysisCanRecoverAfterRestartAndFinish() throws Exception {
        var accepted = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        ((Future<?>) analysisTasks.get(0)).cancel(false);
        var pending = histories.findById(accepted.history().id()).orElseThrow();
        pending.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        histories.saveAndFlush(pending);
        ReflectionTestUtils.setField(analysisService, "workLeases", new WorkLeaseService(dataSource, transactionManager));

        readOnlyScan(() -> analysisService.recoverAnalysisJobs());
        assertThat(analysisTasks).hasSize(2);
        runWorker(analysisTasks.get(1));
        assertThat(analysisService.getHistory(owner.getId(), accepted.history().id()).history().status())
                .isEqualTo(LedgerAiAnalysisStatus.COMPLETED);
    }

    @Test
    void unrecoverableLegacyAnalysisLeavesProcessingEvenInAReadOnlyRecoveryScan() {
        var accepted = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        ((Future<?>) analysisTasks.get(0)).cancel(false);
        var pending = histories.findById(accepted.history().id()).orElseThrow();
        pending.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        pending.setFocusPrompt(null);
        histories.saveAndFlush(pending);
        ReflectionTestUtils.setField(analysisService, "workLeases", new WorkLeaseService(dataSource, transactionManager));

        readOnlyScan(() -> analysisService.recoverAnalysisJobs());
        var failed = analysisService.getHistory(owner.getId(), accepted.history().id());
        assertThat(failed.history().status()).isEqualTo(LedgerAiAnalysisStatus.FAILED);
        assertThat(failed.history().errorMessage()).contains("다시 분석");
        verify(analysisRemote, never()).analyze(any());
    }

    @Test
    void latestMatchingHistoryIsTheNewestWhenSeveralCompletedResultsExist() throws Exception {
        var first = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        runWorker(analysisTasks.get(0));
        var older = histories.findById(first.history().id()).orElseThrow();
        older.setCreatedAt(LocalDateTime.now().minusMinutes(6));
        histories.saveAndFlush(older);
        var second = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        runWorker(analysisTasks.get(1));

        var latest = analysisService.getLatestMatching(owner.getId(), monthlyRequest());
        assertThat(latest.history().id()).isEqualTo(second.history().id());
    }

    @Test
    void structuredImageCandidatesRetainTheirDateAmountAndCategoriesWithoutAutomaticallySavingEntries() throws Exception {
        CategoryGroup food = new CategoryGroup();
        food.setOwner(owner);
        food.setEntryType(EntryType.EXPENSE);
        food.setName("Food");
        food = groups.saveAndFlush(food);
        CategoryDetail cafe = new CategoryDetail();
        cafe.setGroup(food);
        cafe.setName("Cafe");
        cafe = details.saveAndFlush(cafe);
        var parsed = new LedgerImageData.ParsedImageEntry(LocalDate.of(2026, 10, 4), LocalTime.of(13, 5),
                EntryType.EXPENSE, "Fixture cafe", "fixture memo", new BigDecimal("4500"), "Fixture cafe", "Card",
                "Food", "Cafe", "", List.of(), 0.99, List.of());
        when(imageRemote.analyze(any(), anyString(), any())).thenReturn(
                new LedgerImageData.ImageExtraction(true, null, "RECEIPT", "fixture receipt",
                        parsed, List.of(parsed), Map.of()));

        var accepted = imageService.startAnalyze(owner.getId(), image(), "RECEIPT", "structured", "", false);
        runWorker(imageExecutor.tasks.get(0));
        var completed = imageService.getHistory(owner.getId(), accepted.analysisId());
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.result().suggestedEntries()).hasSize(1);
        var suggestion = completed.result().suggestedEntries().get(0);
        assertThat(suggestion.entryDate()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(suggestion.entryTime()).isEqualTo(LocalTime.of(13, 5));
        assertThat(suggestion.amount()).isEqualByComparingTo("4500");
        assertThat(suggestion.categoryGroupId()).isEqualTo(food.getId());
        assertThat(suggestion.categoryDetailId()).isEqualTo(cafe.getId());
        assertThat(entries.countByOwnerIdAndDeletedAtIsNullAndEntryDateBetween(
                owner.getId(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))).isZero();
    }

    @Test
    void imageSerializationFailureDoesNotCreateACompletedHistoryWithAnEmptyResult() throws Exception {
        var mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("fixture") {});
        ReflectionTestUtils.setField(imageService, "objectMapper", mapper);
        var accepted = imageService.startAnalyze(owner.getId(), image(), "AUTO", "serialization", "", false);
        runWorker(imageExecutor.tasks.get(0));
        var failed = imageService.getHistory(owner.getId(), accepted.analysisId());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.result()).isNull();
        assertThat(failed.errorMessage()).contains("결과를 저장할 수 없습니다");
    }

    @Test
    void analysisSerializationFailureAfterInferenceIsPersistedAsFailed() throws Exception {
        var codec = spy(new LedgerAiAnalysisJsonCodec(new ObjectMapper().findAndRegisterModules()));
        doThrow(new IllegalStateException("fixture serialization failure")).when(codec).write(any(LedgerAiAnalysisResponse.class));
        ReflectionTestUtils.setField(analysisService, "aiJsonCodec", codec);
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(analysisService, "analysisFormats"), "aiJsonCodec", codec);
        var accepted = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        runWorker(analysisTasks.get(0));
        var failed = analysisService.getHistory(owner.getId(), accepted.history().id());
        assertThat(failed.history().status()).isEqualTo(LedgerAiAnalysisStatus.FAILED);
        assertThat(failed.history().errorMessage()).contains("fixture serialization failure");
        verify(analysisRemote).analyze(any());
    }

    @Test
    void conditionalWritesAreOwnerScopedAndAdvanceTheOptimisticLockVersion() throws Exception {
        var image = imageService.startAnalyze(owner.getId(), image(), "AUTO", "owner-scope", "", false);
        var analysis = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        var imageVersion = images.findById(image.analysisId()).orElseThrow().getVersion();
        var analysisVersion = histories.findById(analysis.history().id()).orElseThrow().getVersion();
        Long otherOwner = owner.getId() + 100_000;
        assertThat(images.cancelProcessing(image.analysisId(), otherOwner, "unauthorized", LocalDateTime.now())).isZero();
        assertThat(images.failProcessing(image.analysisId(), otherOwner, "unauthorized", "failed", LocalDateTime.now())).isZero();
        assertThat(histories.saveProcessingPayload(analysis.history().id(), otherOwner, "{}")).isZero();
        assertThat(histories.completeProcessing(analysis.history().id(), otherOwner, "unauthorized", "{}")).isZero();
        assertThat(histories.failProcessing(analysis.history().id(), otherOwner, "unauthorized")).isZero();

        runWorker(imageExecutor.tasks.get(0));
        runWorker(analysisTasks.get(0));
        assertThat(images.findById(image.analysisId()).orElseThrow().getVersion()).isEqualTo(imageVersion + 1);
        assertThat(histories.findById(analysis.history().id()).orElseThrow().getVersion()).isEqualTo(analysisVersion + 2);
        assertThat(images.failProcessing(image.analysisId(), owner.getId(), "late failure", "failed", LocalDateTime.now())).isZero();
        assertThat(histories.failProcessing(analysis.history().id(), owner.getId(), "late failure")).isZero();
        assertThat(imageService.getHistory(owner.getId(), image.analysisId()).status()).isEqualTo("COMPLETED");
        assertThat(analysisService.getHistory(owner.getId(), analysis.history().id()).history().status())
                .isEqualTo(LedgerAiAnalysisStatus.COMPLETED);
    }

    private void readOnlyScan(Runnable scan) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.executeWithoutResult(status -> scan.run());
    }

    @Test
    void synchronousRerunSuspendsTheCallersTransactionDuringRemoteInference() {
        var original = analysisService.startAnalyze(owner.getId(), monthlyRequest());
        when(analysisRemote.analyze(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return remoteAnalysis();
        });
        var proxy = transactionalAnalysis();
        var result = new TransactionTemplate(transactionManager).execute(tx -> {
            var response = proxy.rerun(owner.getId(), original.history().id());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            tx.setRollbackOnly();
            return response;
        });
        assertThat(histories.findById(result.historyId()).orElseThrow().getStatus()).isEqualTo(LedgerAiAnalysisStatus.COMPLETED);
    }

    @Test
    void synchronousSerializationFailureNeverPublishesACompletedHistoryWithoutItsResult() {
        var codec = spy(new LedgerAiAnalysisJsonCodec(new ObjectMapper().findAndRegisterModules()));
        doThrow(new IllegalStateException("fixture synchronous serialization failure")).when(codec).write(any(LedgerAiAnalysisResponse.class));
        ReflectionTestUtils.setField(analysisService, "aiJsonCodec", codec);
        assertThatThrownBy(() -> transactionalAnalysis().analyze(owner.getId(), monthlyRequest()))
                .hasMessage("fixture synchronous serialization failure");
        var saved = histories.findAllByOwnerIdOrderByCreatedAtDescIdDesc(owner.getId(), org.springframework.data.domain.PageRequest.of(0,10));
        assertThat(saved.getContent()).hasSize(1).allMatch(h -> h.getStatus() == LedgerAiAnalysisStatus.FAILED);
        assertThat(saved.getContent().get(0).getResultJson()).isNull();
    }

    private LedgerAiAnalysisService transactionalAnalysis() {
        var factory = new ProxyFactory(analysisService);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (LedgerAiAnalysisService) factory.getProxy();
    }

    @Test void synchronousImageInferenceAlsoSuspendsTheCallersTransactionAndPersistsBeforeOuterRollback() {
        when(imageRemote.analyze(any(),anyString(),any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new LedgerImageData.ImageExtraction(true,null,"AUTO","sync image",null,List.of(),Map.of());
        });
        var factory = new ProxyFactory(imageService); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager,new AnnotationTransactionAttributeSource()));
        var proxy = (LedgerOcrService) factory.getProxy();
        var response = new TransactionTemplate(transactionManager).execute(tx -> {
            var result = proxy.analyze(owner.getId(),image(),"AUTO","sync-image","",false);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); tx.setRollbackOnly(); return result;
        });
        assertThat(images.findById(response.analysisId()).orElseThrow().getStatus().name()).isEqualTo("COMPLETED");
        assertThat(imageService.getHistory(owner.getId(),response.analysisId()).result().rawText()).isEqualTo("sync image");
    }

    @Test void synchronousAnalysisHoldsTheSameJobLeaseAsRecoveryAndReleasesItAfterFinalization() {
        var leases = new WorkLeaseService(dataSource,transactionManager);
        ReflectionTestUtils.setField(analysisService,"workLeases",leases);
        AtomicReference<Long> id = new AtomicReference<>();
        when(analysisRemote.analyze(any())).thenAnswer(call -> {
            var pending = histories.findAllByOwnerIdOrderByCreatedAtDescIdDesc(owner.getId(),org.springframework.data.domain.PageRequest.of(0,10)).getContent().get(0);
            id.set(pending.getId()); assertThat(pending.getStatus()).isEqualTo(LedgerAiAnalysisStatus.PROCESSING);
            try (var competing = leases.tryAcquire("analysis-job:"+pending.getId(),Duration.ofMinutes(2))) { assertThat(competing).isNull(); }
            return remoteAnalysis();
        });
        assertThat(transactionalAnalysis().analyze(owner.getId(),monthlyRequest()).historyId()).isNotNull();
        try (var after = leases.tryAcquire("analysis-job:"+id.get(),Duration.ofMinutes(2))) { assertThat(after).isNotNull(); }
    }

    @Test void lostSynchronousAnalysisLeaseDiscardsTheResultAndLeavesRecoveryStateUntouched() {
        var leases = mock(WorkLeaseService.class); var lease = mock(WorkLeaseService.Lease.class);
        AtomicBoolean valid = new AtomicBoolean(true);
        when(leases.tryAcquire(anyString(),any())).thenReturn(lease); when(lease.isValid()).thenAnswer(call -> valid.get());
        ReflectionTestUtils.setField(analysisService,"workLeases",leases);
        when(analysisRemote.analyze(any())).thenAnswer(call -> { valid.set(false); return remoteAnalysis(); });
        assertThatThrownBy(() -> transactionalAnalysis().analyze(owner.getId(),monthlyRequest()))
                .isInstanceOf(com.playdata.calen.common.exception.ServiceUnavailableException.class);
        var pending = histories.findAllByOwnerIdOrderByCreatedAtDescIdDesc(owner.getId(),org.springframework.data.domain.PageRequest.of(0,10)).getContent().get(0);
        assertThat(pending.getStatus()).isEqualTo(LedgerAiAnalysisStatus.PROCESSING); assertThat(pending.getResultJson()).isNull();
        verify(lease).close();
    }

    @Test void excelFinalizationIsOwnerAndVersionScopedAndCannotOverwriteTerminalRows() {
        var job = excelJob("input"); var ref = new ExcelJob(owner.getId(),job.getId(),job.getVersion());
        assertThat(finalizer.complete(new Completion(new ExcelJob(owner.getId()+100000,job.getId(),job.getVersion()),null,"{}",null,LocalDateTime.now()))).isFalse();
        assertThat(finalizer.complete(new Completion(new ExcelJob(owner.getId(),job.getId(),job.getVersion()+1),null,"{}",null,LocalDateTime.now()))).isFalse();
        String result = "{\"fixture\":true}";
        assertThat(finalizer.complete(new Completion(ref,null,result,null,LocalDateTime.now()))).isTrue();
        assertThat(finalizer.fail(new Failure(ref,"late failure",null,LocalDateTime.now()))).isFalse();
        var completed = excel.findById(job.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo("COMPLETED"); assertThat(completed.getVersion()).isEqualTo(job.getVersion()+1);
        assertThat(completed.getResultJson()).isEqualTo(result); assertThat(completed.getInputJson()).isNull(); assertThat(completed.getErrorMessage()).isNull();
        job.setStatus("FAILED");
        assertThatThrownBy(() -> excel.saveAndFlush(job)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }

    @Test void excelVersionChangeDuringInferenceDiscardsTheOldResultAndRecoveryUsesTheNewInput() throws Exception {
        var job = excelJob("old input"); var importer = mock(LedgerAiExcelImportService.class); var tasks = new ArrayList<Runnable>();
        var service = excelService(importer,tasks);
        when(importer.analyzePreparedInput("old input")).thenAnswer(call -> {
            var changed = excel.findById(job.getId()).orElseThrow(); changed.setInputJson("new input"); excel.saveAndFlush(changed); return excelResult("old");
        });
        when(importer.analyzePreparedInput("new input")).thenReturn(excelResult("new"));
        ReflectionTestUtils.invokeMethod(service,"recover"); runWorker(tasks.get(0));
        var pending = excel.findById(job.getId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo("PROCESSING"); assertThat(pending.getInputJson()).isEqualTo("new input"); assertThat(pending.getResultJson()).isNull();
        ReflectionTestUtils.invokeMethod(service,"recover"); runWorker(tasks.get(1));
        assertThat(service.get(owner.getId(),job.getId()).result().fileName()).isEqualTo("new");
    }

    @Test void lateExcelFailureCannotReplaceAnotherWorkersCompletedResult() throws Exception {
        var job = excelJob("input"); var importer = mock(LedgerAiExcelImportService.class); var tasks = new ArrayList<Runnable>();
        var service = excelService(importer,tasks); String result = new ObjectMapper().writeValueAsString(excelResult("winner"));
        when(importer.analyzePreparedInput("input")).thenAnswer(call -> {
            assertThat(finalizer.complete(new Completion(new ExcelJob(owner.getId(),job.getId(),job.getVersion()),null,result,null,LocalDateTime.now()))).isTrue();
            throw new BadRequestException("late failure");
        });
        ReflectionTestUtils.invokeMethod(service,"recover"); runWorker(tasks.get(0));
        assertThat(service.get(owner.getId(),job.getId()).status()).isEqualTo("COMPLETED");
        assertThat(service.get(owner.getId(),job.getId()).result().fileName()).isEqualTo("winner");
        assertThat(service.get(owner.getId(),job.getId()).errorMessage()).isNull();
    }

    @Test void deletingExcelJobDuringInferenceCannotRecreateIt() throws Exception {
        var job = excelJob("input"); var importer = mock(LedgerAiExcelImportService.class); var tasks = new ArrayList<Runnable>();
        var service = excelService(importer,tasks);
        when(importer.analyzePreparedInput("input")).thenAnswer(call -> { excel.deleteById(job.getId()); return excelResult("late"); });
        ReflectionTestUtils.invokeMethod(service,"recover"); runWorker(tasks.get(0));
        assertThat(excel.findById(job.getId())).isEmpty();
    }

    private LedgerExcelAnalysisJob excelJob(String input) {
        var job = new LedgerExcelAnalysisJob(); job.setId(UUID.randomUUID().toString()); job.setOwnerId(owner.getId()); job.setInputJson(input);
        job.setCreatedAt(LocalDateTime.now().minusMinutes(2)); return excel.saveAndFlush(job);
    }

    @Test void excelPreparedInputWaitsForTheProviderWithoutTheCallersTransaction() {
        var properties = new LedgerAiAnalysisProperties(); properties.setEnabled(true); properties.setProvider("lmstudio");
        properties.setLmStudioBaseUrl("http://localhost:1234"); properties.setModel("fixture-model");
        var queue = mock(LedgerAiRequestQueue.class);
        when(queue.execute(any(),any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new BadRequestException("fixture stop before network");
        });
        var json = new ObjectMapper();
        var importer = new LedgerAiExcelImportService(mock(AppUserService.class),properties,
                json,groups,details,mock(com.playdata.calen.ledger.repository.PaymentMethodRepository.class),
                new com.playdata.calen.ledger.application.service.AnalyzeLedgerWorkbookService(
                        new com.playdata.calen.ledger.adapter.out.serialization.JacksonPreparedLedgerWorkbookAdapter(json),
                        new com.playdata.calen.ledger.adapter.out.ai.ExcelWorkbookExtractionHttpAdapter(properties,queue,json,
                                new com.playdata.calen.ledger.adapter.out.ai.LedgerWorkbookResponseCodec(json))));
        var factory = new ProxyFactory(importer); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager,new AnnotationTransactionAttributeSource()));
        var proxy = (LedgerAiExcelImportService) factory.getProxy();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> proxy.analyzePreparedInput("{\"payload\":{}}"))
                    .isInstanceOf(BadRequestException.class).hasMessage("fixture stop before network");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
        verify(queue).execute(any(),any());
    }
    private LedgerExcelPreviewResponse excelResult(String name) { return new LedgerExcelPreviewResponse(name,"sheet",1,0,0,0,List.of(),List.of()); }
    private LedgerAiExcelJobService excelService(LedgerAiExcelImportService importer,List<Runnable> tasks) {
        var leases = mock(WorkLeaseService.class); var lease = mock(WorkLeaseService.Lease.class);
        when(leases.tryAcquire(anyString(),any())).thenReturn(lease); when(lease.isValid()).thenReturn(true);
        return new LedgerAiExcelJobService(importer,excel,leases,new ObjectMapper(),tasks::add,finalizer);
    }

    @Test void workbookUseCaseSuspendsAnOuterTransactionWithoutTheLegacyImporter() {
        var source = new com.playdata.calen.ledger.domain.model.LedgerWorkbookData.SourceReference("row","Sheet",7);
        var workbook = new com.playdata.calen.ledger.domain.model.LedgerWorkbookData.Workbook("book.xlsx","Sheet",
                new com.playdata.calen.ledger.domain.model.LedgerWorkbookData.Choices(java.util.Set.of(),Map.of()),Map.of("row",source),Map.of(),"payload");
        var useCase = new com.playdata.calen.ledger.adapter.in.jobs.NonTransactionalLedgerWorkbookAnalysis(input -> workbook,payload -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(payload).isEqualTo("payload");
            return new com.playdata.calen.ledger.domain.model.LedgerWorkbookData.Extraction(List.of(
                    new com.playdata.calen.ledger.domain.model.LedgerWorkbookData.Candidate("row","",-1,"2026-10-16","","Title","",
                            new com.playdata.calen.ledger.domain.model.LedgerWorkbookData.Amount(BigDecimal.TEN,null),"EXPENSE","","","",List.of())),List.of());
        });
        var factory = new ProxyFactory(useCase); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager,new AnnotationTransactionAttributeSource()));
        var proxy = (com.playdata.calen.ledger.application.port.in.AnalyzeLedgerWorkbookUseCase) factory.getProxy();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(proxy.analyze("persisted").readyRowCount()).isEqualTo(1);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
    }

    private void runWorker(Runnable worker) throws Exception {
        worker.run();
        // FutureTask normally hides the worker's uncaught exceptions from the executor.
        ((Future<?>) worker).get(5, TimeUnit.SECONDS);
    }

    private LedgerAiAnalysisRequest monthlyRequest() {
        return new LedgerAiAnalysisRequest(LedgerAiAnalysisMode.PERIOD, LedgerAiAnalysisPeriod.MONTH,
                null, LocalDate.of(2026, 10, 4), null, null, null, null, null, null);
    }

    @Test void durableImageInputPortSuspendsUploadAndCommitsItsReferenceOutsideCallerRollback() {
        var history=new com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest(); history.setOwner(owner); history=images.saveAndFlush(history);
        Long id=history.getId(); Long version=history.getVersion();
        var storage=mock(LedgerImageStoragePort.class); when(storage.supportsStorage()).thenReturn(true);
        var storedAt=LocalDateTime.of(2026,10,16,12,0);
        when(storage.store(any(),any(),any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new LedgerImageStorageData.StoredImage("fixture/direct-input",storedAt);
        });
        var useCase=new com.playdata.calen.ledger.adapter.in.image.NonTransactionalLedgerImageInputStore(storage,
                new com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerImageInputReferenceAdapter(images));
        var factory=new ProxyFactory(useCase); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager,new AnnotationTransactionAttributeSource()));
        var proxy=(StoreLedgerImageInputUseCase)factory.getProxy();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(proxy.store(owner.getId(),id,new com.playdata.calen.ledger.adapter.in.image.MultipartLedgerImageContent(image()))).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); tx.setRollbackOnly();
        });
        var result=images.findById(id).orElseThrow(); assertThat(result.getVersion()).isEqualTo(version+1);
        assertThat(result.getStatus().name()).isEqualTo("PROCESSING"); assertThat(result.getResultJson()).isNull();
        assertThat(result.getImageObjectKey()).isEqualTo("fixture/direct-input"); assertThat(result.getImageStoredAt()).isEqualTo(storedAt);
        verify(storage,never()).delete(anyString());
    }

    @Test void durableImageReferenceStillAcceptsCancellationWithoutResettingStatusOrVersion() {
        var history=new com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest(); history.setOwner(owner); history=images.saveAndFlush(history);
        Long id=history.getId(); Long version=history.getVersion(); var cancelledAt=LocalDateTime.of(2026,10,16,12,0);
        var storage=mock(LedgerImageStoragePort.class); when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(any(),any(),any())).thenAnswer(call -> {
            assertThat(images.cancelProcessing(id,owner.getId(),"cancelled during upload",cancelledAt)).isEqualTo(1);
            return new LedgerImageStorageData.StoredImage("fixture/cancelled-input",cancelledAt);
        });
        var useCase=new com.playdata.calen.ledger.application.service.StoreLedgerImageInputService(storage,
                new com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerImageInputReferenceAdapter(images));
        assertThat(useCase.store(owner.getId(),id,new com.playdata.calen.ledger.adapter.in.image.MultipartLedgerImageContent(image()))).isTrue();
        var result=images.findById(id).orElseThrow(); assertThat(result.getVersion()).isEqualTo(version+2);
        assertThat(result.getStatus().name()).isEqualTo("CANCELLED"); assertThat(result.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(result.getImageObjectKey()).isEqualTo("fixture/cancelled-input"); assertThat(result.getResultJson()).isNull();
        verify(storage,never()).delete(anyString());
    }

    @Test void wrongOwnerImageReferenceCannotAttachAndCompensatesOnlyItsOwnUpload() {
        var history=new com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest(); history.setOwner(owner); history=images.saveAndFlush(history);
        Long id=history.getId(); Long version=history.getVersion();
        var storage=mock(LedgerImageStoragePort.class); when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(any(),any(),any())).thenReturn(new LedgerImageStorageData.StoredImage("fixture/wrong-owner-input",LocalDateTime.now()));
        var useCase=new com.playdata.calen.ledger.application.service.StoreLedgerImageInputService(storage,
                new com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerImageInputReferenceAdapter(images));
        assertThatThrownBy(() -> useCase.store(owner.getId()+100000,id,new com.playdata.calen.ledger.adapter.in.image.MultipartLedgerImageContent(image())))
                .isInstanceOf(NotFoundException.class);
        var result=images.findById(id).orElseThrow(); assertThat(result.getVersion()).isEqualTo(version);
        assertThat(result.getImageObjectKey()).isNull(); assertThat(result.getStatus().name()).isEqualTo("PROCESSING");
        verify(storage).delete("fixture/wrong-owner-input");
    }

    private void installStorage(LedgerImageStoragePort storage) {
        ReflectionTestUtils.setField(imageService,"imageStorageService",storage);
        ReflectionTestUtils.setField(imageService,"imageInputWriter",
                new com.playdata.calen.ledger.application.service.StoreLedgerImageInputService(storage,
                        new com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerImageInputReferenceAdapter(images)));
    }

    private Result remoteAnalysis() {
        return new Result(true, null, "fixture spending summary", List.of(), List.of(),
                List.of(), List.of("Review dining expenses"), List.of(), List.of(), List.of(), List.of(),
                List.of(), "", "", null);
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("file", "receipt.jpg", "image/jpeg", jpegBytes());
    }

    private byte[] jpegBytes() {
        return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0};
    }

    private static final class CapturingImageExecutor extends ThreadPoolTaskExecutor {
        private final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
    }
}
