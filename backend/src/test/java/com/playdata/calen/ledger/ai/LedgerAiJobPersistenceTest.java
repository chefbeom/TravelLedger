package com.playdata.calen.ledger.ai;

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
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisRequest;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisResponse;
import com.playdata.calen.ledger.dto.OverviewResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrImageStorageService;
import com.playdata.calen.ledger.ocr.LedgerOcrProperties;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient;
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
    @Autowired private CategoryGroupRepository groups;
    @Autowired private CategoryDetailRepository details;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    private AppUser owner;
    private LedgerOcrService imageService;
    private LedgerAiAnalysisService analysisService;
    private LedgerOcrRemoteClient imageRemote;
    private LedgerAiRemoteClient analysisRemote;
    private CapturingImageExecutor imageExecutor;
    private List<Runnable> analysisTasks;

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
        imageRemote = mock(LedgerOcrRemoteClient.class);
        when(imageRemote.analyze(any(), anyString(), any())).thenReturn(
                new LedgerOcrRemoteClient.RemoteAnalyzeResponse(true, null, "AUTO", "fixture receipt",
                        null, List.of(), Map.of()));
        imageService = new LedgerOcrService(userService, new LedgerOcrProperties(), properties, imageRemote,
                images, groups, details, entries, mapper);
        imageExecutor = new CapturingImageExecutor();
        ReflectionTestUtils.setField(imageService, "ledgerOcrTaskExecutor", imageExecutor);

        var statistics = mock(StatisticsService.class);
        when(statistics.getOverview(any(), any(), any())).thenReturn(new OverviewResponse(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                BigDecimal.ZERO, new BigDecimal("15000"), new BigDecimal("-15000"), 1));
        analysisRemote = mock(LedgerAiRemoteClient.class);
        when(analysisRemote.analyze(any())).thenReturn(remoteAnalysis());
        var text = new LedgerAiAnalysisTextSanitizer();
        analysisService = new LedgerAiAnalysisService(userService, statistics, entries, histories, properties,
                new LedgerAiAnalysisStatusService(properties), analysisRemote,
                new LedgerAiAnalysisMetrics(properties, new StaticListableBeanFactory().getBeanProvider(
                        io.micrometer.core.instrument.MeterRegistry.class)),
                new LedgerAiAnalysisJsonCodec(mapper), text, new LedgerAiAnalysisPayloadBuilder(text),
                new LedgerAiAnalysisReportMerger(text));
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
        var storage = mock(LedgerOcrImageStorageService.class);
        when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(any(), any(), any())).thenReturn(
                new LedgerOcrImageStorageService.StoredImage("fixture/image", LocalDateTime.now()));
        when(storage.load(anyString(), any(), any())).thenReturn(
                new LedgerOcrImageStorageService.StoredImageContent(jpegBytes(), "image/jpeg", "receipt.jpg"));
        ReflectionTestUtils.setField(imageService, "imageStorageService", storage);

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
            return new LedgerOcrRemoteClient.RemoteAnalyzeResponse(true, null, "AUTO", "late response", null, List.of(), Map.of());
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
        var storage = mock(LedgerOcrImageStorageService.class);
        when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(any(), any(), any())).thenAnswer(invocation -> {
            imageService.cancelHistory(owner.getId(), invocation.getArgument(1));
            return new LedgerOcrImageStorageService.StoredImage("fixture/cancelled-image", LocalDateTime.now());
        });
        ReflectionTestUtils.setField(imageService, "imageStorageService", storage);

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
        var storage = mock(LedgerOcrImageStorageService.class);
        when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(any(), any(), any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(1);
            images.cancelProcessing(id, owner.getId(), "cancelled elsewhere", LocalDateTime.now());
            images.deleteById(id);
            return new LedgerOcrImageStorageService.StoredImage("fixture/deleted-image", LocalDateTime.now());
        });
        ReflectionTestUtils.setField(imageService, "imageStorageService", storage);

        assertThatThrownBy(() -> imageService.startAnalyze(owner.getId(), image(), "AUTO", "delete-upload", "", false))
                .isInstanceOf(NotFoundException.class);
        assertThat(images.findByClientRequestIdAndOwnerId("delete-upload", owner.getId())).isEmpty();
        assertThat(imageExecutor.tasks).isEmpty();
        verify(storage).delete("fixture/deleted-image");
        verify(imageRemote, never()).analyze(any(), anyString(), any());
    }

    @Test
    void storedImageCanRecoverAfterRestartUsingThePersistedInputReference() throws Exception {
        var storage = mock(LedgerOcrImageStorageService.class);
        when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(any(), any(), any())).thenReturn(
                new LedgerOcrImageStorageService.StoredImage("fixture/recovered-image", LocalDateTime.now()));
        when(storage.load(anyString(), any(), any())).thenReturn(
                new LedgerOcrImageStorageService.StoredImageContent(jpegBytes(), "image/jpeg", "receipt.jpg"));
        ReflectionTestUtils.setField(imageService, "imageStorageService", storage);
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
    void deletedImageIsNotRecreatedByALateCompletion() throws Exception {
        var accepted = imageService.startAnalyze(owner.getId(), image(), "AUTO", "deleted", "", false);
        when(imageRemote.analyze(any(), anyString(), any())).thenAnswer(invocation -> {
            images.deleteById(accepted.analysisId());
            return new LedgerOcrRemoteClient.RemoteAnalyzeResponse(true, null, "AUTO", "late response", null, List.of(), Map.of());
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
        var parsed = new LedgerOcrRemoteClient.RemoteParsedResult(LocalDate.of(2026, 10, 4), LocalTime.of(13, 5),
                EntryType.EXPENSE, "Fixture cafe", "fixture memo", new BigDecimal("4500"), "Fixture cafe", "Card",
                "Food", "Cafe", "", List.of(), 0.99, List.of());
        when(imageRemote.analyze(any(), anyString(), any())).thenReturn(
                new LedgerOcrRemoteClient.RemoteAnalyzeResponse(true, null, "RECEIPT", "fixture receipt",
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

    private void runWorker(Runnable worker) throws Exception {
        worker.run();
        // FutureTask normally hides the worker's uncaught exceptions from the executor.
        ((Future<?>) worker).get(5, TimeUnit.SECONDS);
    }

    private LedgerAiAnalysisRequest monthlyRequest() {
        return new LedgerAiAnalysisRequest(LedgerAiAnalysisMode.PERIOD, LedgerAiAnalysisPeriod.MONTH,
                null, LocalDate.of(2026, 10, 4), null, null, null, null, null, null);
    }

    private LedgerAiRemoteResponse remoteAnalysis() {
        return new LedgerAiRemoteResponse(true, null, "fixture spending summary", List.of(), List.of(),
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
