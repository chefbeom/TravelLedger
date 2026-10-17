package com.playdata.calen.travel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache;
import com.playdata.calen.travel.application.port.in.WriteTravelPlanUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.dto.TravelPlanSummaryResponse;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=true", "app.seed.allow-insecure-default-credentials=true",
        "spring.datasource.url=jdbc:h2:mem:travel-plan-refactoring-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelPlanRefactoringIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelBudgetItemRepository budgets;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired TravelMediaAssetRepository media;
    @Autowired TravelRouteSegmentRepository routes;
    @Autowired TravelPlanShareRepository shares;
    @Autowired DriveItemRepository driveItems;
    @Autowired TravelDriveLinkService drive;
    @Autowired WriteTravelPlanUseCase writes;
    @Autowired TravelPlanStore store;
    @Autowired TravelPlanAttachmentsPort attachments;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean TravelClusterRefreshCoordinator clusters;

    private final LocalDate start = LocalDate.of(2026,10,10), end = LocalDate.of(2026,10,15);
    private record Fixture(long plan, long owner, long budget, List<Long> records, List<Long> media, List<Long> routes, long share) {}
    @BeforeEach void clearMocks() { clearInvocations(redis, storage, clusters); }

    @Test void creationAndUpdateKeepJsonMetricsAndExistingPublicShareFlags() throws Exception {
        long id = createPlan();
        String updated = mvc.perform(put("/api/travel/plans/{id}", id).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(planBody())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(updated).isEqualTo(json.writeValueAsString(emptySummary(id)));
        Fixture fixture = populate(id);
        var expected = new TravelPlanSummaryResponse(id, "Trip", "Japan", start, end, "KRW", 2, "COMPLETED", "#ABCDEF", "note",
                new BigDecimal("50.00"), new BigDecimal("300.33"), 1, 2, 1, 2, 2, new BigDecimal("3.579"), 90, 1000);
        updated = mvc.perform(put("/api/travel/plans/{id}", id).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(planBody())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(updated).isEqualTo(json.writeValueAsString(expected));
        assertThat(plans.findById(fixture.plan()).orElseThrow().getPublicShared()).isTrue();
        assertThat(plans.findById(fixture.plan()).orElseThrow().getPublicSharedAt()).isEqualTo(LocalDateTime.of(2026,10,10,0,0));
    }

    @Test void deletionRollbackPreservesAllRowsDriveLinksAndFilesThenCommitCleansThem() throws Exception {
        Fixture fixture = populate(createPlan());
        clearMocks();
        var transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            writes.delete(fixture.owner(), fixture.plan());
            plans.flush();
            assertThat(plans.existsById(fixture.plan())).isFalse();
            assertThat(media.existsById(fixture.media().get(0))).isFalse();
            verifyNoInteractions(storage);
            verify(clusters, never()).dispatch(any());
            throw new IllegalStateException("failure after SQL deletion");
        })).isInstanceOf(IllegalStateException.class).hasMessage("failure after SQL deletion");
        assertRowsPresent(fixture);
        assertThat(driveItems.findByOwner_IdAndSourceTypeAndSourceReference(fixture.owner(), TravelDriveLinkService.SOURCE_TRAVEL_PLAN, ""+fixture.plan())).isPresent();
        assertThat(driveItems.findByOwner_IdAndSourceTypeAndSourceReference(fixture.owner(), TravelDriveLinkService.SOURCE_TRAVEL_GPX, ""+fixture.routes().get(1))).isPresent();
        verifyNoInteractions(storage);
        verify(clusters, never()).dispatch(any());
        clearMocks();
        transaction.executeWithoutResult(tx -> {
            writes.delete(fixture.owner(), fixture.plan());
            plans.flush();
            verifyNoInteractions(storage);
            verify(clusters).markDirtyInCurrentTransaction(fixture.owner());
            verify(clusters, never()).dispatch(any());
        });
        assertThat(plans.existsById(fixture.plan())).isFalse();
        assertThat(budgets.existsById(fixture.budget())).isFalse();
        fixture.records().forEach(id -> assertThat(records.existsById(id)).isFalse());
        fixture.media().forEach(id -> assertThat(media.existsById(id)).isFalse());
        fixture.routes().forEach(id -> assertThat(routes.existsById(id)).isFalse());
        assertThat(shares.existsById(fixture.share())).isFalse();
        assertThat(driveItems.findByOwner_IdAndSourceTypeAndSourceReference(fixture.owner(), TravelDriveLinkService.SOURCE_TRAVEL_PLAN, ""+fixture.plan())).isEmpty();
        assertThat(driveItems.findByOwner_IdAndSourceTypeAndSourceReference(fixture.owner(), TravelDriveLinkService.SOURCE_TRAVEL_GPX, ""+fixture.routes().get(1))).isEmpty();
        verify(storage).deleteImageWithThumbnailsQuietly("test-only/photo.jpg", "image/jpeg");
        verify(storage).deleteImageWithThumbnailsQuietly("test-only/receipt.pdf", "application/pdf");
        verify(storage).deleteQuietly("test-only/route.gpx");
        verify(clusters).dispatch(fixture.owner());
        verify(redis, times(2)).delete(TravelSummaryCache.plansKey(fixture.owner()), TravelSummaryCache.portfolioKey(fixture.owner()),
                TravelSummaryCache.mapKey(fixture.owner()), TravelSummaryCache.PUBLIC_TRIPS_KEY);
        var order = inOrder(redis, clusters, storage);
        order.verify(redis).delete(TravelSummaryCache.plansKey(fixture.owner()), TravelSummaryCache.portfolioKey(fixture.owner()),
                TravelSummaryCache.mapKey(fixture.owner()), TravelSummaryCache.PUBLIC_TRIPS_KEY);
        order.verify(clusters).markDirtyInCurrentTransaction(fixture.owner());
        order.verify(redis).delete(TravelSummaryCache.plansKey(fixture.owner()), TravelSummaryCache.portfolioKey(fixture.owner()),
                TravelSummaryCache.mapKey(fixture.owner()), TravelSummaryCache.PUBLIC_TRIPS_KEY);
        order.verify(clusters).dispatch(fixture.owner());
        order.verify(storage, atLeastOnce()).deleteImageWithThumbnailsQuietly(anyString(), anyString());
        order.verify(storage).deleteQuietly("test-only/route.gpx");
    }

    @Test void ownershipAuthenticationCsrfAndValidationRejectChangesWithoutCleanup() throws Exception {
        long id = createPlan(); clearMocks();
        mvc.perform(post("/api/travel/plans").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(planBody())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/travel/plans").with(as("hana")).contentType(MediaType.APPLICATION_JSON).content(planBody())).andExpect(status().isForbidden());
        mvc.perform(put("/api/travel/plans/{id}", id).with(as("minsu")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(planBody())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/travel/plans/{id}", id).with(as("minsu")).with(csrf())).andExpect(status().isNotFound());
        var invalid = json.readTree(planBody()); ((com.fasterxml.jackson.databind.node.ObjectNode)invalid).put("endDate", "2026-10-09");
        mvc.perform(put("/api/travel/plans/{id}", id).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(invalid))).andExpect(status().isBadRequest());
        assertThat(plans.findById(id).orElseThrow().getEndDate()).isEqualTo(end);
        verifyNoInteractions(storage, redis, clusters);
    }

    @Test void cleanupFailureAfterCommitDoesNotSkipOtherFilesOrCacheAndClusterCallbacks() throws Exception {
        Fixture fixture = populate(createPlan()); clearMocks();
        doThrow(new IllegalStateException("test failure")).when(storage).deleteImageWithThumbnailsQuietly("test-only/photo.jpg", "image/jpeg");
        mvc.perform(delete("/api/travel/plans/{id}", fixture.plan()).with(as("hana")).with(csrf())).andExpect(status().isOk());
        assertThat(plans.existsById(fixture.plan())).isFalse();
        verify(storage).deleteImageWithThumbnailsQuietly("test-only/receipt.pdf", "application/pdf");
        verify(storage).deleteQuietly("test-only/route.gpx");
        verify(clusters).dispatch(fixture.owner());
        verify(redis, times(2)).delete(TravelSummaryCache.plansKey(fixture.owner()), TravelSummaryCache.portfolioKey(fixture.owner()),
                TravelSummaryCache.mapKey(fixture.owner()), TravelSummaryCache.PUBLIC_TRIPS_KEY);
    }

    @Test void persistenceAndAttachmentAdaptersRequireTransaction() {
        assertThatThrownBy(() -> store.requireOwnedPlan(userId("hana"), 1L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> attachments.removePlanLinks(1L)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void clusterDirtyFailureRollsBackWholeDeletionWithoutDeletingFiles() throws Exception {
        Fixture fixture = populate(createPlan()); clearMocks();
        doThrow(new IllegalStateException("dirty state unavailable")).when(clusters).markDirtyInCurrentTransaction(fixture.owner());
        assertThatThrownBy(() -> writes.delete(fixture.owner(), fixture.plan())).hasMessage("dirty state unavailable");
        assertRowsPresent(fixture);
        assertThat(driveItems.findByOwner_IdAndSourceTypeAndSourceReference(fixture.owner(), TravelDriveLinkService.SOURCE_TRAVEL_PLAN, ""+fixture.plan())).isPresent();
        verifyNoInteractions(storage);
        verify(clusters, never()).dispatch(any());
        verify(redis).delete(TravelSummaryCache.plansKey(fixture.owner()), TravelSummaryCache.portfolioKey(fixture.owner()),
                TravelSummaryCache.mapKey(fixture.owner()), TravelSummaryCache.PUBLIC_TRIPS_KEY);
    }

    private void assertRowsPresent(Fixture f) {
        assertThat(plans.existsById(f.plan())).isTrue(); assertThat(budgets.existsById(f.budget())).isTrue();
        f.records().forEach(id -> assertThat(records.existsById(id)).isTrue());
        f.media().forEach(id -> assertThat(media.existsById(id)).isTrue());
        f.routes().forEach(id -> assertThat(routes.existsById(id)).isTrue());
        assertThat(shares.existsById(f.share())).isTrue();
    }

    private Fixture populate(long planId) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var plan = plans.findById(planId).orElseThrow();
            plan.setPublicShared(true); plan.setPublicSharedAt(LocalDateTime.of(2026,10,10,0,0));
            var budget = new TravelBudgetItem(); budget.setPlan(plan); budget.setCategory("Flight"); budget.setTitle("Budget");
            budget.setAmount(new BigDecimal("50.00")); budget.setAmountKrw(new BigDecimal("50.00")); budgets.save(budget);
            var ledger = record(plan, TravelRecordType.LEDGER, "100.11");
            var legacy = record(plan, null, "200.22");
            var memory = record(plan, TravelRecordType.MEMORY, "0.00");
            var photo = asset(plan, memory, "photo.jpg", "image/jpeg");
            var receipt = asset(plan, ledger, "receipt.pdf", "application/pdf");
            var manual = route(plan, "1.234", null, 1000); var gpx = route(plan, "2.345", 90, null);
            gpx.setSourceType(TravelRouteSourceType.GPX);
            gpx.setGpxFilesJson("[{\"originalFileName\":\"route.gpx\",\"storagePath\":\"test-only/route.gpx\",\"contentType\":\"application/gpx+xml\",\"fileSize\":1}]");
            var share = new TravelPlanShare(); share.setPlan(plan); share.setSharedBy(plan.getOwner());
            share.setRecipient(users.findByLoginId("minsu").orElseThrow()); share.setCreatedAt(LocalDateTime.now()); shares.save(share);
            drive.ensurePlanFolder(plan); drive.linkMediaAsset(photo); drive.linkMediaAsset(receipt);
            drive.replaceRouteGpxLinks(gpx, List.of(new TravelDriveLinkService.TravelLinkedFile("route.gpx", "test-only/route.gpx", "application/gpx+xml", 1L)));
            return new Fixture(planId, plan.getOwner().getId(), budget.getId(), List.of(ledger.getId(), legacy.getId(), memory.getId()),
                    List.of(photo.getId(), receipt.getId()), List.of(manual.getId(), gpx.getId()), share.getId());
        });
    }

    private TravelExpenseRecord record(TravelPlan plan, TravelRecordType type, String amount) {
        var record = new TravelExpenseRecord(); record.setPlan(plan); record.setRecordType(type); record.setExpenseDate(start);
        record.setCategory("Spot"); record.setTitle("record"); record.setAmount(new BigDecimal(amount)); record.setAmountKrw(new BigDecimal(amount));
        return records.save(record);
    }

    private TravelMediaAsset asset(TravelPlan plan, TravelExpenseRecord record, String name, String contentType) {
        var asset = new TravelMediaAsset(); asset.setPlan(plan); asset.setRecord(record); asset.setUploadedBy(plan.getOwner());
        asset.setOriginalFileName(name); asset.setStoredFileName(name); asset.setStoragePath("test-only/"+name);
        asset.setContentType(contentType); asset.setFileSize(1L); asset.setMediaType(contentType.startsWith("image/") ? TravelMediaType.PHOTO : TravelMediaType.RECEIPT);
        return media.save(asset);
    }

    private TravelRouteSegment route(TravelPlan plan, String distance, Integer minutes, Integer steps) {
        var route = new TravelRouteSegment(); route.setPlan(plan); route.setRouteDate(start); route.setTitle("route");
        route.setRoutePathJson("[]"); route.setDistanceKm(new BigDecimal(distance)); route.setDurationMinutes(minutes); route.setStepCount(steps);
        return routes.save(route);
    }

    private long createPlan() throws Exception {
        String raw = mvc.perform(post("/api/travel/plans").with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(planBody())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(raw).path("id").asLong();
        assertThat(raw).isEqualTo(json.writeValueAsString(emptySummary(id)));
        return id;
    }
    private TravelPlanSummaryResponse emptySummary(long id) {
        return new TravelPlanSummaryResponse(id, "Trip", "Japan", start, end, "KRW", 2, "COMPLETED", "#ABCDEF", "note",
                new BigDecimal("0.00"), new BigDecimal("0.00"), 0, 0, 0, 0, 0, new BigDecimal("0.000"), 0, 0);
    }
    private String planBody() throws Exception {
        return json.writeValueAsString(Map.of("name"," Trip ", "destination"," Japan ", "startDate",start.toString(), "endDate",end.toString(),
                "homeCurrency","krw", "headCount",2, "status","completed", "colorHex","#abcdef", "memo"," note "));
    }
    private long userId(String login) { return users.findByLoginId(login).orElseThrow().getId(); }
    private RequestPostProcessor as(String login) { return user(AppUserPrincipal.from(users.findByLoginId(login).orElseThrow())); }
}
