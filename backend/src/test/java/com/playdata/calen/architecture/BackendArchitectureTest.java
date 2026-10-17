package com.playdata.calen.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.playdata.calen.sharing.application.service.CreateRecordShareService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.asm.*;

/** Checks compiled dependencies, including implementation bodies and generic signatures. */
class BackendArchitectureTest {
    private static final String SHARING = "com.playdata.calen.sharing.";
    private static final List<String> FEATURES = List.of("sharing", "ledger", "recordmap", "travel");
    private static final Pattern TYPE_REFERENCE = Pattern.compile("L([A-Za-z0-9_$/]+)");

    @Test void migratedCoresDependOnlyOnTheirPortsAndPureBusinessValues() throws Exception {
        Path classes = Path.of(CreateRecordShareService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Path> targets = new ArrayList<>();
        for (String feature : FEATURES) {
            String prefix = "com.playdata.calen." + feature + ".";
            try (var files = Files.walk(classes.resolve("com/playdata/calen/" + feature))) {
                var featureTargets = files.filter(path -> path.toString().endsWith(".class")).filter(path -> {
                    String name = classes.relativize(path).toString().replace('\\', '.').replace('/', '.');
                    return name.startsWith(prefix + "application.") || name.startsWith(prefix + "domain.model.") || name.startsWith(prefix + "domain.policy.");
                }).toList();
                assertThat(featureTargets).as("the migrated %s slice must remain covered", feature).hasSizeGreaterThanOrEqualTo(5);
                targets.addAll(featureTargets);
            }
        }
        assertThat(targets).as("the migrated slice must not disappear from this check").hasSizeGreaterThanOrEqualTo(10);
        List<String> violations = new ArrayList<>();
        for (Path file : targets) {
            String origin = classes.relativize(file).toString().replace('\\', '.').replace('/', '.');
            for (String dependency : dependencies(Files.readAllBytes(file))) {
                if (!allowed(origin, dependency)) violations.add(origin + " -> " + dependency);
            }
        }
        assertThat(violations).as("core must not depend on Spring/JPA/JSON, adapters, legacy DTOs/services or foreign entities").isEmpty();
    }

    @Test void dependencyReaderDetectsMethodBodyAndGenericOnlyReferences() throws IOException {
        String resource = "/" + ForbiddenDependencyFixture.class.getName().replace('.', '/') + ".class";
        try (var bytes = getClass().getResourceAsStream(resource)) {
            assertThat(bytes).isNotNull();
            var dependencies = dependencies(bytes.readAllBytes());
            assertThat(dependencies).contains("com.fasterxml.jackson.databind.ObjectMapper", "com.playdata.calen.ledger.dto.LedgerEntryResponse");
            assertThat(allowed(SHARING + "application.Example", "com.fasterxml.jackson.databind.ObjectMapper")).isFalse();
            assertThat(allowed(SHARING + "application.port.in.Example", SHARING + "application.service.CreateRecordShareService")).isFalse();
            assertThat(allowed(SHARING + "domain.policy.Example", SHARING + "application.port.out.ShareRequestsPort")).isFalse();
            assertThat(allowed(SHARING + "application.Example", "com.playdata.calen.common.exception.GlobalExceptionHandler")).isFalse();
            assertThat(allowed(SHARING + "application.Example", "java.sql.Connection")).isFalse();
            assertThat(allowed(SHARING + "application.Example", "java.net.http.HttpClient")).isFalse();
            assertThat(allowed(SHARING + "application.Example", "java.nio.file.Files")).isFalse();
            assertThat(allowed("com.playdata.calen.ledger.application.Example", SHARING + "application.port.in.Example")).isFalse();
            assertThat(allowed("com.playdata.calen.ledger.domain.policy.Example", "com.playdata.calen.ledger.domain.LedgerEntry")).isFalse();
            assertThat(allowed("com.playdata.calen.travel.domain.policy.Example", "com.playdata.calen.travel.domain.TravelPlan")).isFalse();
        }
    }

    @Test void clusterExecutionAndProjectionNoLongerDependOnTheLegacyTravelService() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.travel.service.TravelClusterRefreshCoordinator.class,
                com.playdata.calen.travel.adapter.out.cluster.TravelClusterRefreshAdapter.class,
                com.playdata.calen.travel.adapter.out.persistence.JpaTravelClusterProjectionAdapter.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).as(type.getSimpleName())
                        .doesNotContain("com.playdata.calen.travel.service.TravelService", "com.playdata.calen.travel.service.TravelPhotoClusterService");
            }
        }
    }

    @Test void workbookPreparationDependsOnTheAnalysisPortNotHttpOrProviderResponseImplementations() throws IOException {
        Class<?> type = com.playdata.calen.ledger.service.LedgerAiExcelImportService.class;
        try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertThat(bytes).isNotNull();
            assertThat(dependencies(bytes.readAllBytes())).contains("com.playdata.calen.ledger.application.port.in.AnalyzeLedgerWorkbookUseCase")
                    .doesNotContain("org.springframework.web.client.RestClient", "com.playdata.calen.ledger.ai.LedgerAiRequestQueue",
                            "com.playdata.calen.ledger.adapter.out.ai.ExcelWorkbookExtractionHttpAdapter", "com.playdata.calen.ledger.adapter.out.ai.LedgerWorkbookResponseCodec");
        }
    }

    @Test void routeExecutionPayloadAndPersistenceAdaptersDoNotCallTheLargeTravelService() throws IOException {
        for(Class<?> type:List.of(com.playdata.calen.travel.adapter.in.web.TravelRouteCommandController.class,
                com.playdata.calen.travel.adapter.in.transaction.TransactionalTravelRoutes.class,
                com.playdata.calen.travel.adapter.out.persistence.JpaTravelRouteAdapter.class,
                com.playdata.calen.travel.adapter.out.route.TravelRoutePayloadAdapter.class)) {
            try(var bytes=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).as(type.getSimpleName()).doesNotContain("com.playdata.calen.travel.service.TravelService");
            }
        }
        assertThat(allowed("com.playdata.calen.travel.domain.model.Example","com.playdata.calen.travel.domain.TravelRouteSourceType")).isTrue();
        assertThat(allowed("com.playdata.calen.travel.domain.model.Example","com.playdata.calen.travel.domain.TravelRouteSegment")).isFalse();
    }

    @Test void exhibitReadBoundariesDoNotCallTheLegacyServiceOrOwnedMediaCache() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.travel.adapter.in.web.TravelExhibitController.class,
                com.playdata.calen.travel.adapter.in.transaction.TransactionalTravelExhibits.class,
                com.playdata.calen.travel.adapter.out.persistence.JpaTravelExhibitReadAdapter.class,
                com.playdata.calen.travel.adapter.out.security.TravelPublicMediaTokenAdapter.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).as(type.getSimpleName()).doesNotContain(
                        "com.playdata.calen.travel.service.TravelService", "com.playdata.calen.travel.adapter.out.cache.OwnedTravelMediaCache");
            }
        }
    }

    @Test void sharingManagementBoundariesDoNotCallTheLargeTravelServiceOrExposeManagedEntities() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.travel.adapter.in.web.TravelSharingController.class,
                com.playdata.calen.travel.adapter.in.transaction.TransactionalTravelSharing.class,
                com.playdata.calen.travel.adapter.out.persistence.JpaTravelSharingAdapter.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).as(type.getSimpleName())
                        .doesNotContain("com.playdata.calen.travel.service.TravelService");
            }
        }
        assertThat(allowed("com.playdata.calen.travel.application.service.Example", "com.playdata.calen.account.domain.AppUser")).isFalse();
        assertThat(allowed("com.playdata.calen.travel.application.service.Example", "com.playdata.calen.travel.domain.TravelPlanShare")).isFalse();
    }

    @Test void atlasAndCommunityBoundariesDoNotCallTheLegacyServiceOrOwnerCaches() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.travel.adapter.in.web.TravelAtlasController.class,
                com.playdata.calen.travel.adapter.in.transaction.TransactionalTravelAtlas.class,
                com.playdata.calen.travel.adapter.out.persistence.JpaTravelAtlasAdapter.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).as(type.getSimpleName())
                        .doesNotContain("com.playdata.calen.travel.service.TravelService", "com.playdata.calen.common.cache.RedisCacheService",
                                "com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache");
            }
        }
    }

    @Test void travelLedgerReflectionBoundariesDoNotDependOnTheLargeServiceOrDeletedBridge() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.travel.adapter.in.web.TravelLedgerReflectionController.class,
                com.playdata.calen.ledger.adapter.in.transaction.TransactionalTravelLedgerReflection.class,
                com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerTravelAdapter.class,
                com.playdata.calen.ledger.adapter.out.entry.LinkedLedgerWriter.class,
                com.playdata.calen.ledger.adapter.out.travel.TravelBridgeEffects.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).as(type.getSimpleName()).doesNotContain(
                        "com.playdata.calen.travel.service.TravelService", "com.playdata.calen.ledger.service.LedgerTravelBridgeService");
            }
        }
        assertThat(com.playdata.calen.ledger.adapter.out.entry.LinkedLedgerWriter.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(com.playdata.calen.ledger.service.LedgerEntryService.class));
    }

    @Test void ownedPlanReadBoundariesDoNotCallTheLargeServiceOrCreateASeparateFlightRegistry() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.travel.adapter.in.web.OwnedTravelPlansController.class,
                com.playdata.calen.travel.adapter.in.transaction.TransactionalOwnedTravelPlans.class,
                com.playdata.calen.travel.adapter.out.persistence.JpaOwnedTravelPlansAdapter.class,
                com.playdata.calen.travel.adapter.out.cache.OwnedTravelPlansCache.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).as(type.getSimpleName()).doesNotContain(
                        "com.playdata.calen.travel.service.TravelService", "com.playdata.calen.common.cache.SingleFlight");
            }
        }
    }

    @Test void referenceReadBoundariesUsePortsAndTheLegacyFacadeContainsOnlyUseCaseFields() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.travel.adapter.in.web.TravelReferenceController.class,
                com.playdata.calen.travel.adapter.in.transaction.TransactionalTravelReference.class,
                com.playdata.calen.travel.adapter.out.persistence.JpaTravelReferenceAdapter.class,
                com.playdata.calen.travel.adapter.out.exchangerate.TravelRateAdapter.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull(); assertThat(dependencies(bytes.readAllBytes())).doesNotContain("com.playdata.calen.travel.service.TravelService");
            }
        }
        assertThat(com.playdata.calen.travel.service.TravelService.class.getDeclaredFields()).allSatisfy(field ->
                assertThat(field.getType().getName()).as(field.getName()).contains(".application.port.in."));
    }

    @Test void localReportValuesAndMappingCannotDependOnTheOrchestratorOrCappedProviderRequest() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.ledger.domain.policy.LedgerLocalReportPolicy.class,
                com.playdata.calen.ledger.adapter.in.ai.LedgerLocalReportMapper.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull(); var values = dependencies(bytes.readAllBytes());
                assertThat(values).noneMatch(name -> name.equals("com.playdata.calen.ledger.ai.LedgerAiAnalysisService")
                        || name.startsWith("com.playdata.calen.ledger.ai.LedgerAiAnalysisService$"));
                assertThat(values).doesNotContain("com.playdata.calen.ledger.domain.model.LedgerAnalysisData$Request",
                        "com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData$LedgerAiN8nPayload",
                        "com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort");
            }
        }
        String origin = "com.playdata.calen.ledger.domain.policy.LedgerLocalReportPolicy";
        assertThat(allowed(origin, "java.text.NumberFormat")).isTrue();
        assertThat(allowed(origin, "java.text.DateFormat")).isFalse();
        assertThat(allowed(origin, "java.nio.file.Files")).isFalse();
        assertThat(allowed("com.playdata.calen.ledger.domain.policy.OtherPolicy", "java.text.NumberFormat")).isFalse();
    }

    @Test void analysisPreparationKeepsCalendarAndFullDatasetSeparateFromTheOrchestratorAndProvider() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.ledger.domain.policy.LedgerAnalysisCalendarPolicy.class,
                com.playdata.calen.ledger.application.service.PrepareLedgerAnalysisDatasetService.class,
                com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisDatasetPreparation.class,
                com.playdata.calen.ledger.adapter.out.persistence.LedgerAnalysisDatasetQueryAdapter.class,
                com.playdata.calen.ledger.adapter.support.LedgerAnalysisPreparationMapper.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                var values = dependencies(bytes.readAllBytes());
                assertThat(values).as(type.getSimpleName()).noneMatch(name ->
                        name.equals("com.playdata.calen.ledger.ai.LedgerAiAnalysisService")
                        || name.startsWith("com.playdata.calen.ledger.ai.LedgerAiAnalysisService$"));
                assertThat(values).doesNotContain("com.playdata.calen.ledger.domain.model.LedgerAnalysisData$Request",
                        "com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData$LedgerAiN8nPayload",
                        "com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort");
            }
        }
        for (Class<?> type : List.of(com.playdata.calen.ledger.domain.LedgerAiAnalysisMode.class,
                com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod.class,
                com.playdata.calen.ledger.domain.LedgerAiComparisonPreset.class,
                com.playdata.calen.ledger.domain.ComparisonUnit.class)) {
            assertThat(type.isEnum()).as(type.getSimpleName()).isTrue();
            assertThat(allowed("com.playdata.calen.ledger.domain.policy.LedgerAnalysisCalendarPolicy", type.getName())).isTrue();
        }
        assertThat(allowed("com.playdata.calen.ledger.domain.policy.LedgerAnalysisCalendarPolicy",
                "com.playdata.calen.ledger.domain.LedgerEntry")).isFalse();
    }

    @Test void datasetPreparationDoesNotCreateAnEncompassingOrSuspendedTransaction() {
        for (Class<?> type : List.of(com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisDatasetPreparation.class,
                com.playdata.calen.ledger.adapter.out.persistence.LedgerAnalysisDatasetQueryAdapter.class)) {
            assertThat(type.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
            assertThat(type.getDeclaredMethods()).allSatisfy(method ->
                    assertThat(method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse());
        }
    }

    @Test void recoveryRuntimeBindingsCannotCallLegacyOrchestratorsOrAllocateSeparateTaskRegistries() throws IOException {
        Class<?> type = com.playdata.calen.ledger.adapter.out.jobs.LedgerAiRecoverySources.class;
        try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertThat(bytes).isNotNull();
            assertThat(dependencies(bytes.readAllBytes())).doesNotContain(
                    "com.playdata.calen.ledger.ai.LedgerAiAnalysisService", "com.playdata.calen.ledger.ocr.LedgerOcrService",
                    "com.playdata.calen.ledger.service.LedgerAiExcelJobService", "java.util.concurrent.ConcurrentHashMap",
                    "com.playdata.calen.common.cache.SingleFlight", "com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort",
                    "com.playdata.calen.ledger.application.port.out.LedgerImageExtractionPort");
        }
    }

    @Test void recoverySourcesKeepTheExistingTransactionContextInsteadOfWrappingTheWholeScan() {
        Class<?> type = com.playdata.calen.ledger.adapter.out.jobs.LedgerAiRecoverySources.class;
        assertThat(type.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(type.getDeclaredMethods()).allSatisfy(method ->
                assertThat(method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse());
    }

    @Test void historyBoundariesDoNotCallTheLargeOrchestratorOrProviderAndStatusRemainsAPureEnum() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.ledger.application.service.ManageLedgerAnalysisHistoryService.class,
                com.playdata.calen.ledger.adapter.support.LedgerAnalysisHistoryMapper.class,
                com.playdata.calen.ledger.adapter.out.persistence.LedgerAnalysisHistoryQueryAdapter.class,
                com.playdata.calen.ledger.adapter.out.serialization.LedgerAnalysisHistoryJsonAdapter.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull(); var values = dependencies(bytes.readAllBytes());
                assertThat(values).noneMatch(name -> name.equals("com.playdata.calen.ledger.ai.LedgerAiAnalysisService") || name.startsWith("com.playdata.calen.ledger.ai.LedgerAiAnalysisService$"));
                assertThat(values).doesNotContain("com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort",
                        "com.playdata.calen.ledger.domain.model.LedgerAnalysisData$Request", "com.playdata.calen.ledger.application.port.in.FinalizeLedgerAiJobUseCase");
            }
        }
        assertThat(com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus.class.isEnum()).isTrue();
        assertThat(allowed("com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData", "com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus")).isTrue();
        assertThat(allowed("com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData", "com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory")).isFalse();
    }

    @Test void historyCrudTransactionsStayRequiredWhileMatchingDecodingAndQueriesHaveNoNewAnnotation() throws Exception {
        Class<?> type = com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisHistories.class;
        for (var method : type.getDeclaredMethods()) {
            var annotation = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            if (List.of("search", "detail", "delete", "deleteAll").contains(method.getName())) {
                assertThat(annotation).isNotNull(); assertThat(annotation.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
                assertThat(annotation.readOnly()).isEqualTo(List.of("search", "detail").contains(method.getName()));
            } else assertThat(annotation).isNull();
        }
        Class<?> query = com.playdata.calen.ledger.adapter.out.persistence.LedgerAnalysisHistoryQueryAdapter.class;
        assertThat(query.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(query.getDeclaredMethods()).allSatisfy(method -> assertThat(method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse());
    }

    @Test void admissionRuntimeAndDraftsCannotCallTheLargeOrchestratorOrCreateSeparateRegistries() throws IOException {
        var targets = new ArrayList<Class<?>>();
        targets.addAll(List.of(com.playdata.calen.ledger.adapter.out.jobs.LedgerAnalysisAdmissions.class.getNestMembers()));
        targets.add(com.playdata.calen.ledger.adapter.support.LedgerAnalysisHistoryDrafts.class);
        targets.add(com.playdata.calen.ledger.domain.policy.LedgerAnalysisAdmissionPolicy.class);
        targets.add(com.playdata.calen.ledger.application.service.StartLedgerAnalysisService.class);
        for (Class<?> type : targets) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull(); var values = dependencies(bytes.readAllBytes());
                assertThat(values).noneMatch(name -> name.equals("com.playdata.calen.ledger.ai.LedgerAiAnalysisService") || name.startsWith("com.playdata.calen.ledger.ai.LedgerAiAnalysisService$"));
                assertThat(values).doesNotContain("com.playdata.calen.common.cache.SingleFlight", "java.util.concurrent.ConcurrentHashMap",
                        "com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort");
            }
        }
        assertThat(allowed("com.playdata.calen.ledger.application.service.StartLedgerAnalysisService", "com.playdata.calen.common.exception.TooManyRequestsException")).isTrue();
        assertThat(allowed("com.playdata.calen.ledger.application.service.StartLedgerAnalysisService", "org.springframework.web.server.ResponseStatusException")).isFalse();
    }

    @Test void admissionHasNoEncompassingTransactionAndItsLeaseContractDoesNotExecuteIo() {
        for (Class<?> type : com.playdata.calen.ledger.adapter.out.jobs.LedgerAnalysisAdmissions.class.getNestMembers()) {
            assertThat(type.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
            assertThat(type.getDeclaredMethods()).allSatisfy(method -> assertThat(method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse());
        }
        assertThat(com.playdata.calen.ledger.application.port.out.LedgerAnalysisAdmissionPort.AdmissionLease.class.getDeclaredMethods())
                .allSatisfy(method -> assertThat(java.lang.reflect.Modifier.isAbstract(method.getModifiers())).isTrue());
    }

    @Test void workerAndFormatsHaveNoLargeServiceNestedTypesOrIndependentTaskRegistries() throws IOException {
        var targets = new ArrayList<Class<?>>();
        targets.add(com.playdata.calen.ledger.application.service.ExecuteLedgerAnalysisService.class);
        targets.add(com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisJobExecution.class);
        targets.add(com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerAnalysisWorkStore.class);
        targets.addAll(List.of(com.playdata.calen.ledger.adapter.out.analysis.LedgerAnalysisFormats.class.getNestMembers()));
        targets.addAll(List.of(com.playdata.calen.ledger.adapter.out.observability.LedgerAnalysisWorkTelemetry.class.getNestMembers()));
        for (Class<?> type : targets) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull(); var values = dependencies(bytes.readAllBytes());
                assertThat(values).noneMatch(name -> name.equals("com.playdata.calen.ledger.ai.LedgerAiAnalysisService") || name.startsWith("com.playdata.calen.ledger.ai.LedgerAiAnalysisService$"));
                assertThat(values).doesNotContain("com.playdata.calen.common.cache.SingleFlight", "java.util.concurrent.ConcurrentHashMap");
            }
        }
    }

    @Test void directWorkerEntrySuspendsTransactionsButFormatAndStoreAdaptersDoNotBroadenThem() throws Exception {
        var method = com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisJobExecution.class.getMethod("process", Long.class, Long.class,
                com.playdata.calen.ledger.domain.model.LedgerAnalysisAdmissionData.AnalysisPlan.class, java.util.function.BooleanSupplier.class);
        assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED);
        for (Class<?> type : List.of(com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerAnalysisWorkStore.class,
                com.playdata.calen.ledger.adapter.out.analysis.LedgerAnalysisFormats.class)) {
            assertThat(type.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
            assertThat(type.getDeclaredMethods()).allSatisfy(member -> assertThat(member.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse());
        }
        assertThat(com.playdata.calen.ledger.application.port.out.LedgerAnalysisWorkFormats.Prepared.class.getDeclaredMethods())
                .allSatisfy(member -> assertThat(java.lang.reflect.Modifier.isAbstract(member.getModifiers())).isTrue());
    }

    @Test void publicMapLinkAndLoginPreviewBoundariesDoNotCallTheLargeServiceOrOwnerCaches() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.travel.adapter.in.web.TravelMapLinkController.class,
                com.playdata.calen.travel.adapter.in.transaction.TransactionalTravelMapLinks.class,
                com.playdata.calen.travel.adapter.out.persistence.JpaTravelMapLinkAdapter.class,
                com.playdata.calen.travel.adapter.out.security.TravelMapLinkRuntime.class,
                com.playdata.calen.travel.service.TravelLoginMapPreviewService.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).as(type.getSimpleName())
                        .doesNotContain("com.playdata.calen.travel.service.TravelService", "com.playdata.calen.common.cache.RedisCacheService",
                                "com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache");
            }
        }
    }

    @Test void ownedMapReadBoundariesDoNotCallTheLegacyServiceOrBypassTheSharedCacheRegistry() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.travel.adapter.in.web.OwnedTravelMapController.class,
                com.playdata.calen.travel.adapter.in.transaction.TransactionalOwnedTravelMap.class,
                com.playdata.calen.travel.adapter.out.persistence.JpaOwnedTravelMapReadAdapter.class,
                com.playdata.calen.travel.adapter.out.cache.OwnedTravelMapCache.class,
                com.playdata.calen.travel.adapter.out.cluster.OwnedTravelMapClusters.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).as(type.getSimpleName())
                        .doesNotContain("com.playdata.calen.travel.service.TravelService", "com.playdata.calen.common.cache.SingleFlight");
            }
        }
    }

    @Test void ownedMediaBoundariesDoNotCallTheLegacyTravelServiceOrMoveHttpIntoTheCore() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.travel.adapter.in.web.OwnedTravelMediaController.class,
                com.playdata.calen.travel.adapter.in.web.TravelMediaHttpResponses.class,
                com.playdata.calen.travel.adapter.in.transaction.TransactionalOwnedTravelMedia.class,
                com.playdata.calen.travel.adapter.out.persistence.JpaTravelMediaAdapter.class,
                com.playdata.calen.travel.adapter.out.storage.TravelMediaStorageAdapter.class,
                com.playdata.calen.travel.adapter.out.cache.OwnedTravelMediaCache.class)) {
            try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).as(type.getSimpleName())
                        .doesNotContain("com.playdata.calen.travel.service.TravelService");
            }
        }
        assertThat(allowed("com.playdata.calen.travel.application.service.Example", "org.springframework.http.ResponseEntity")).isFalse();
        assertThat(allowed("com.playdata.calen.travel.application.service.Example", "com.playdata.calen.travel.service.TravelMediaStorageService")).isFalse();
    }

    @Test void ownedClusterAdaptersDoNotCallTheLegacyTravelServiceOrExposeJpaValuesToTheCore() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.travel.adapter.in.web.TravelPhotoClusterController.class,
                com.playdata.calen.travel.adapter.in.transaction.TransactionalTravelClusterDetails.class,
                com.playdata.calen.travel.adapter.out.persistence.JpaTravelClusterDetailAdapter.class,
                com.playdata.calen.travel.adapter.out.cluster.TravelClusterDetailSynchronizationAdapter.class)) {
            try (var bytes=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).as(type.getSimpleName())
                        .doesNotContain("com.playdata.calen.travel.service.TravelService");
            }
        }
        for (var method : com.playdata.calen.travel.application.port.in.ManageTravelPhotoClustersUseCase.class.getDeclaredMethods()) {
            assertThat(method.getReturnType()).isEqualTo(com.playdata.calen.travel.domain.model.TravelClusterDetailData.Page.class);
        }
        assertThat(allowed("com.playdata.calen.travel.domain.model.Example","com.playdata.calen.travel.domain.TravelMediaType")).isTrue();
        assertThat(allowed("com.playdata.calen.travel.domain.model.Example","com.playdata.calen.travel.domain.TravelMediaAsset")).isFalse();
    }

    @Test void imageWorkflowUsesPureExtractionValuesNotHttpOrJsonWireImplementations() throws IOException {
        Class<?> type = com.playdata.calen.ledger.ocr.LedgerOcrService.class;
        try (var bytes = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertThat(bytes).isNotNull();
            assertThat(dependencies(bytes.readAllBytes())).contains("com.playdata.calen.ledger.application.port.out.LedgerImageExtractionPort")
                    .doesNotContain("com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient", "org.springframework.web.client.RestClient",
                            "com.playdata.calen.ledger.adapter.out.ai.ImageLedgerExtractionHttpAdapter",
                            "com.playdata.calen.ledger.adapter.out.ai.LedgerImageResponseCodec$RemoteParsedResult");
        }
    }

    @Test void spendingAnalysisBoundaryUsesExactPureRequestAndResultNotObjectOrWireDtos() throws Exception {
        var port=com.playdata.calen.ledger.application.port.out.LedgerSpendingAnalysisPort.class;
        var method=port.getDeclaredMethod("analyze",com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Request.class);
        assertThat(method.getReturnType()).isEqualTo(com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Result.class);
        Class<?> type=com.playdata.calen.ledger.ai.LedgerAiAnalysisService.class;
        try (var bytes=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
            assertThat(bytes).isNotNull();
            assertThat(dependencies(bytes.readAllBytes())).contains(port.getName())
                    .doesNotContain("com.playdata.calen.ledger.ai.LedgerAiRemoteClient","org.springframework.web.client.RestClient",
                            "com.playdata.calen.ledger.adapter.out.ai.LedgerAnalysisWireResponse",
                            "com.playdata.calen.ledger.adapter.out.ai.LedgerAiLmStudioClient");
        }
    }

    @Test void payloadPreparationNoLongerDependsOnTheLargeOrchestratorOrItsNestedDataTypes() throws IOException {
        Class<?> type=com.playdata.calen.ledger.ai.LedgerAiAnalysisPayloadBuilder.class;
        try (var bytes=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
            assertThat(bytes).isNotNull();
            assertThat(dependencies(bytes.readAllBytes())).noneMatch(name -> name.equals("com.playdata.calen.ledger.ai.LedgerAiAnalysisService")
                    || name.startsWith("com.playdata.calen.ledger.ai.LedgerAiAnalysisService$"));
        }
    }

    @Test void dependencyReaderNormalizesArrayOwnersWithoutAllowingForeignArrayDependencies() throws IOException {
        String resource = "/" + ArrayDependencyFixture.class.getName().replace('.', '/') + ".class";
        try (var bytes = getClass().getResourceAsStream(resource)) {
            assertThat(bytes).isNotNull();
            var dependencies = dependencies(bytes.readAllBytes());
            assertThat(dependencies).contains("com.fasterxml.jackson.databind.ObjectMapper");
            assertThat(dependencies).noneMatch(name -> name.startsWith("["));
            assertThat(allowed(SHARING + "application.Example", "com.fasterxml.jackson.databind.ObjectMapper")).isFalse();
        }
    }

    private static boolean allowed(String origin, String dependency) {
        // Only explicitly scoped binary-upload declarations may name I/O types; no core executes I/O.
        if ((origin.equals("com.playdata.calen.ledger.application.port.out.LedgerImageStoragePort$Upload")
                || origin.equals("com.playdata.calen.ledger.application.port.out.LedgerImageStoragePort$Upload.class"))
                && (dependency.equals("java.io.InputStream") || dependency.equals("java.io.IOException"))) return true;
        if ((origin.equals("com.playdata.calen.travel.application.port.out.TravelRouteUploadPort$Upload")
                || origin.equals("com.playdata.calen.travel.application.port.out.TravelRouteUploadPort$Upload.class"))
                && dependency.equals("java.io.IOException")) return true;
        if (dependency.equals("java.lang.Runtime") || dependency.equals("java.lang.ProcessBuilder")) return false;
        // Preserve the existing Korean number formatter, without opening java.text or I/O to other cores.
        if ((origin.equals("com.playdata.calen.ledger.domain.policy.LedgerLocalReportPolicy")
                || origin.equals("com.playdata.calen.ledger.domain.policy.LedgerLocalReportPolicy.class"))
                && dependency.equals("java.text.NumberFormat")) return true;
        if (dependency.startsWith("java.lang.") || dependency.startsWith("java.math.")
                || dependency.startsWith("java.time.") || dependency.startsWith("java.util.")) return true;
        if (dependency.equals("com.playdata.calen.common.exception.BadRequestException")
                || dependency.equals("com.playdata.calen.common.exception.NotFoundException")
                || dependency.equals("com.playdata.calen.common.exception.TooManyRequestsException")) return true;
        String feature = origin.substring("com.playdata.calen.".length()).split("\\.")[0];
        String prefix = "com.playdata.calen." + feature + ".";
        if (feature.equals("sharing") && (dependency.equals(prefix + "domain.RecordShareKind") || dependency.equals(prefix + "domain.RecordShareStatus"))) return true;
        if (feature.equals("ledger") && (dependency.equals(prefix + "domain.EntryType") || dependency.equals(prefix + "domain.PaymentMethodKind"))) return true;
        if (feature.equals("ledger") && (dependency.equals(prefix + "domain.LedgerAiAnalysisMode")
                || dependency.equals(prefix + "domain.LedgerAiAnalysisPeriod") || dependency.equals(prefix + "domain.LedgerAiComparisonPreset")
                || dependency.equals(prefix + "domain.ComparisonUnit") || dependency.equals(prefix + "domain.LedgerAiAnalysisStatus"))) return true;
        if (feature.equals("travel") && dependency.equals(prefix + "domain.TravelPlanStatus")) return true;
        if (feature.equals("travel") && dependency.equals(prefix + "domain.TravelRecordType")) return true;
        if (feature.equals("travel") && dependency.equals(prefix + "domain.TravelMediaType")) return true;
        if (feature.equals("travel") && dependency.equals(prefix + "domain.TravelRouteSourceType")) return true;
        if (feature.equals("travel") && dependency.equals(prefix + "domain.TravelRouteTransportMode")) return true;
        if (feature.equals("travel") && dependency.equals(prefix + "domain.TravelRouteLineStyle")) return true;
        if (dependency.startsWith(prefix + "domain.model.") || dependency.startsWith(prefix + "domain.policy.")) return true;
        if (origin.startsWith(prefix + "domain.")) return false;
        if (dependency.startsWith(prefix + "application.port.")) return true;
        return !origin.startsWith(prefix + "application.port.") && dependency.startsWith(prefix + "application.service.");
    }

    @Test void streamingExceptionNeverAllowsIoInUseCasesPoliciesOrOtherPorts() {
        for (var method : com.playdata.calen.ledger.application.port.out.LedgerImageStoragePort.Upload.class.getDeclaredMethods()) {
            assertThat(java.lang.reflect.Modifier.isAbstract(method.getModifiers())).as("upload contract must not execute IO itself").isTrue();
            assertThat(method.isDefault()).isFalse();
        }
        String upload="com.playdata.calen.ledger.application.port.out.LedgerImageStoragePort$Upload";
        assertThat(allowed(upload,"java.io.InputStream")).isTrue();
        assertThat(allowed(upload+".class","java.io.IOException")).isTrue();
        assertThat(allowed(upload,"java.nio.file.Files")).isFalse();
        assertThat(allowed("com.playdata.calen.ledger.application.service.StoreLedgerImageInputService","java.io.InputStream")).isFalse();
        assertThat(allowed("com.playdata.calen.ledger.application.port.out.OtherPort","java.io.InputStream")).isFalse();
        assertThat(allowed("com.playdata.calen.ledger.domain.policy.Example","java.io.InputStream")).isFalse();
    }

    @Test void gpxByteReadDeclarationIsNarrowAndDoesNotAllowCoreOrOtherPortIo() {
        String upload="com.playdata.calen.travel.application.port.out.TravelRouteUploadPort$Upload";
        for(var method:com.playdata.calen.travel.application.port.out.TravelRouteUploadPort.Upload.class.getDeclaredMethods()) {
            assertThat(java.lang.reflect.Modifier.isAbstract(method.getModifiers())).isTrue();
            assertThat(method.isDefault()).isFalse();
        }
        assertThat(allowed(upload,"java.io.IOException")).isTrue();
        assertThat(allowed(upload+".class","java.io.IOException")).isTrue();
        assertThat(allowed(upload,"java.io.InputStream")).isFalse();
        assertThat(allowed(upload,"java.nio.file.Files")).isFalse();
        assertThat(allowed("com.playdata.calen.travel.application.service.ManageTravelRouteFilesService","java.io.IOException")).isFalse();
        assertThat(allowed("com.playdata.calen.travel.application.port.out.OtherPort","java.io.IOException")).isFalse();
    }

    @Test void gpxAdaptersUsePortsWithoutCallingTheLargeTravelService() throws IOException {
        for(Class<?> type:List.of(com.playdata.calen.travel.adapter.in.web.TravelRouteFilesController.class,
                com.playdata.calen.travel.adapter.in.transaction.TransactionalTravelRouteFiles.class,
                com.playdata.calen.travel.adapter.out.storage.TravelRouteUploadAdapter.class)) {
            try(var bytes=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).as(type.getSimpleName()).doesNotContain("com.playdata.calen.travel.service.TravelService");
            }
        }
    }

    @Test void embeddingWorkerUsesTheBatchUseCaseAndPurePortsDoNotExposeWireModelsOrJdbcClaims() throws IOException {
        Class<?> worker=com.playdata.calen.ledger.embedding.sync.LedgerEmbeddingSyncWorker.class;
        try(var bytes=worker.getResourceAsStream("/"+worker.getName().replace('.','/')+".class")) {
            assertThat(bytes).isNotNull();
            assertThat(dependencies(bytes.readAllBytes())).contains("com.playdata.calen.ledger.application.port.in.SynchronizeLedgerEmbeddingsUseCase")
                    .doesNotContain("com.playdata.calen.common.embedding.EmbeddingApiClient","com.playdata.calen.common.embedding.EmbeddingApiException",
                            "com.playdata.calen.ledger.embedding.sync.LedgerEmbeddingSyncStore","com.playdata.calen.ledger.embedding.sync.LedgerEmbeddingSyncStore$Claim");
        }
        for(Class<?> type:List.of(com.playdata.calen.ledger.application.port.out.LedgerEmbeddingJobsPort.class,
                com.playdata.calen.ledger.application.port.out.LedgerEmbeddingIndexPort.class)) {
            try(var bytes=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).noneMatch(name->name.startsWith("com.playdata.calen.common.embedding.")
                        ||name.startsWith("com.playdata.calen.ledger.embedding."));
            }
        }
    }

    @Test void imageWorkflowAndControllerNoLongerExposeTheConcreteStorageImplementation() throws IOException {
        for (Class<?> type : List.of(com.playdata.calen.ledger.ocr.LedgerOcrService.class,com.playdata.calen.ledger.web.LedgerOcrController.class)) {
            try (var bytes=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                assertThat(bytes).isNotNull();
                assertThat(dependencies(bytes.readAllBytes())).doesNotContain("com.playdata.calen.ledger.ocr.LedgerOcrImageStorageService",
                        "com.playdata.calen.ledger.adapter.out.storage.MinioLedgerImageStorageAdapter","io.minio.MinioClient");
            }
        }
    }

    private static Set<String> dependencies(byte[] bytes) {
        Set<String> result = new TreeSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            void name(String internal) {
                if (internal == null) return;
                if (internal.startsWith("[")) { signature(internal); return; }
                result.add(internal.replace('/', '.'));
            }
            void signature(String signature) {
                if (signature == null) return;
                var matcher = TYPE_REFERENCE.matcher(signature);
                while (matcher.find()) name(matcher.group(1));
            }
            void value(Object value) {
                if (value instanceof Type type) signature(type.getDescriptor());
                if (value instanceof Handle handle) { name(handle.getOwner()); signature(handle.getDesc()); }
            }
            @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
                name(parent); signature(signature); if (interfaces != null) Arrays.stream(interfaces).forEach(this::name);
            }
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { signature(descriptor); return null; }
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                signature(descriptor); signature(signature); value(value);
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { signature(descriptor); return null; }
                };
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                signature(descriptor); signature(signature); if (exceptions != null) Arrays.stream(exceptions).forEach(this::name);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { signature(descriptor); return null; }
                    @Override public void visitTypeInsn(int opcode, String type) { name(type); }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) { name(owner); signature(descriptor); }
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) { name(owner); signature(descriptor); }
                    @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                        signature(descriptor); value(bootstrap); for (Object argument : arguments) value(argument);
                    }
                    @Override public void visitLdcInsn(Object value) { value(value); }
                    @Override public void visitMultiANewArrayInsn(String descriptor, int dimensions) { signature(descriptor); }
                    @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { name(type); }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

    static class ForbiddenDependencyFixture {
        List<com.playdata.calen.ledger.dto.LedgerEntryResponse> genericOnly;
        Object methodBodyOnly() { return new com.fasterxml.jackson.databind.ObjectMapper(); }
    }

    static class ArrayDependencyFixture {
        Object clonedArray(com.fasterxml.jackson.databind.ObjectMapper[] input) { return input.clone(); }
    }
}
