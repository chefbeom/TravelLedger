package com.playdata.calen.travel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.travel.adapter.out.persistence.JpaTravelMapLinkAdapter;
import com.playdata.calen.travel.adapter.out.security.TravelMapLinkRuntime;
import com.playdata.calen.travel.application.port.in.TravelMapLinksUseCase;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelMapLinkData.*;
import com.playdata.calen.travel.dto.AdminLoginMapPreviewUpdateRequest;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:travel-map-links;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelMapLinkRefactoringIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired TravelMediaAssetRepository assets;
    @Autowired TravelRouteSegmentRepository routes;
    @Autowired TravelMapShareLinkRepository links;
    @Autowired TravelMapLinksUseCase useCase;
    @Autowired TravelLoginMapPreviewService preview;
    @Autowired TravelMapLinkRuntime runtime;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean JpaTravelMapLinkAdapter store;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelClusterRefreshCoordinator coordinator;
    JpaTravelMapLinkAdapter target;
    record Fixture(long owner, long plan, long memory, long photo, long extra, long otherMemory, long otherPhoto, long ledgerPhoto, long receipt, long route) {}

    @BeforeEach void unwrap() { target = AopTestUtils.getUltimateTargetObject(store); reset(target); }

    @Test void creationIsAuthenticatedOwnerScopedAndAtomicWhileIncompletePrivatePlansRemainLinkShareable() throws Exception {
        var f = fixture(); var other = fixture();
        mvc.perform(post("/api/travel/map-shares").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"planIds\":[" + f.plan() + "]}"))
                .andExpect(status().isUnauthorized());
        long before = links.count();
        mvc.perform(post("/api/travel/map-shares").with(user(principal(f))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planIds\":[" + other.plan() + "]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/travel/map-shares").with(user(principal(f))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planIds\":[" + f.plan() + "],\"excludedMediaIds\":[" + other.photo() + "]}")).andExpect(status().isBadRequest());
        assertThat(links.count()).isEqualTo(before);
        var created = mvc.perform(post("/api/travel/map-shares").with(user(principal(f))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"  Synthetic link  \",\"planIds\":[" + f.plan() + "," + f.plan() + ",0,null]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Synthetic link")).andExpect(jsonPath("$.planIds.length()").value(1)).andReturn();
        var token = json.readTree(created.getResponse().getContentAsByteArray()).get("token").asText();
        assertThat(token).matches("[A-Za-z0-9_-]{32}");
        assertThat(useCase.map(token).overview().plans()).isEqualTo(1);
        verifyNoInteractions(redis, storage, coordinator);
    }

    @Test void anonymousMapClusterAndDownloadsApplyAllExclusionsWithoutPublishingThePlan() throws Exception {
        var f = fixture(); var other = fixture();
        var link = useCase.create(f.owner(), new Draft("Scope", List.of(f.plan()), List.of(f.otherMemory()), List.of(f.extra()), List.of(f.route())));
        mvc.perform(get("/api/travel/public/map-shares/{token}", link.token())).andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.markerCount").value(1)).andExpect(jsonPath("$.overview.photoMarkerCount").value(1))
                .andExpect(jsonPath("$.overview.routeCount").value(0)).andExpect(jsonPath("$.overview.photoPins[0].mediaId").value(f.photo()));
        long cluster = useCase.map(link.token()).overview().clusters().get(0).id();
        mvc.perform(get("/api/travel/public/map-shares/{token}/photo-clusters/{id}", link.token(), cluster).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalPhotoCount").value(1))
                .andExpect(jsonPath("$.photos[0].contentUrl").value("/api/travel/public/map-shares/" + link.token() + "/media/" + f.photo() + "/content"));
        for (long denied : List.of(f.otherPhoto(), f.extra(), f.ledgerPhoto(), f.receipt(), other.photo())) {
            mvc.perform(get("/api/travel/public/map-shares/{token}/media/{id}/content", link.token(), denied)).andExpect(status().isNotFound());
        }
        verifyNoInteractions(storage);
        when(storage.loadAsResource(path(f.photo()))).thenReturn(new ByteArrayResource(new byte[] {1, 2, 3}));
        mvc.perform(get("/api/travel/public/map-shares/{token}/media/{id}/content", link.token(), f.photo()))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[] {1, 2, 3}));
        assertThat(plans.findById(f.plan()).orElseThrow().getPublicShared()).isFalse();
        verifyNoInteractions(redis, coordinator);
    }

    @Test void everyReadRechecksActiveLinkAndCurrentOwnershipInsteadOfTrustingAnOwnerMapCache() throws Exception {
        var f = fixture(); var other = fixture(); var link = create(f);
        var cluster = useCase.map(link.token()).overview().clusters().get(0).id();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> users.findById(f.owner()).orElseThrow().setActive(false));
        // Existing bearer links do not require their owner to log in or remain active.
        assertThat(useCase.map(link.token()).overview().plans()).isEqualTo(1);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> plans.findById(f.plan()).orElseThrow().setOwner(users.getReferenceById(other.owner())));
        var empty = useCase.map(link.token());
        assertThat(empty.overview().plans()).isZero(); assertThat(empty.link().scope().plans()).containsExactly(f.plan());
        mvc.perform(get("/api/travel/public/map-shares/{token}/media/{id}/content", link.token(), f.photo())).andExpect(status().isNotFound());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> links.findByTokenAndActiveTrue(link.token()).orElseThrow().setActive(false));
        mvc.perform(get("/api/travel/public/map-shares/{token}", link.token())).andExpect(status().isNotFound());
        mvc.perform(get("/api/travel/public/map-shares/{token}/photo-clusters/{id}", link.token(), cluster)).andExpect(status().isNotFound());
        verifyNoInteractions(redis, storage, coordinator);
    }

    @Test void malformedPersistedScopeKeepsLegacyFailSoftDecodingAndDoesNotInventNewAccessRules() {
        var f = fixture(); var link = useCase.create(f.owner(), new Draft(null, List.of(f.plan()), null, List.of(f.extra()), null));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> links.findByTokenAndActiveTrue(link.token()).orElseThrow().setExcludedMediaIdsJson("broken"));
        assertThat(useCase.map(link.token()).overview().photoCount()).isEqualTo(3);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> links.findByTokenAndActiveTrue(link.token()).orElseThrow().setPlanIdsJson("null"));
        assertThat(useCase.map(link.token()).overview().plans()).isZero(); assertThat(useCase.preview(link.token()).enabled()).isFalse();
        verifyNoInteractions(redis, storage, coordinator);
    }

    @Test void overviewUsesStoredRouteSnapshotButLoginPreviewStillResolvesOriginalGpxAndStripsPrivateMetadata() throws Exception {
        var f = fixture(); var link = create(f);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var route = routes.findById(f.route()).orElseThrow(); route.setSourceType(TravelRouteSourceType.GPX);
            route.setGpxFilesJson("[{\"originalFileName\":\"private.gpx\",\"storagePath\":\"test-only/route.gpx\",\"contentType\":\"application/gpx+xml\",\"fileSize\":3}]");
        });
        assertThat(useCase.map(link.token()).overview().routes().get(0).points().get(0).latitude()).isEqualByComparingTo("37.5");
        verifyNoInteractions(storage);
        when(storage.loadAsResource("test-only/route.gpx")).thenReturn(new ByteArrayResource(
                "<gpx><trk><trkseg><trkpt lat=\"35.123456\" lon=\"139.987654\"/><trkpt lat=\"35.223456\" lon=\"139.887654\"/></trkseg></trk></gpx>".getBytes(StandardCharsets.UTF_8)));
        var value = useCase.preview(link.token());
        assertThat(value.routes().get(0).points().get(0).latitude()).isEqualByComparingTo("35.123");
        var wire = json.writeValueAsString(com.playdata.calen.travel.adapter.support.TravelMapLinkMapper.preview(value));
        assertThat(wire).doesNotContain(link.token(), "Synthetic place", "private.gpx", "test-only", "linkedMemoryId", "ownerLogin");
        verify(storage).loadAsResource("test-only/route.gpx");
    }

    @Test void configuredLoginPreviewKeepsNoStoreThumbnailOnlyBehaviorAndFailsClosedAfterRevocation() throws Exception {
        var f = fixture(); var link = create(f);
        preview.updateAdminSettings(new AdminLoginMapPreviewUpdateRequest(true, link.token(), false));
        mvc.perform(get("/api/travel/public/login-map-preview")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.markers[0].thumbnailUrl").value("/api/travel/public/login-map-preview/markers/1/thumbnail"));
        mvc.perform(get("/api/travel/public/login-map-preview/markers/1/thumbnail")).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(storage, never()).loadAsResource(anyString());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> links.findByTokenAndActiveTrue(link.token()).orElseThrow().setActive(false));
        clearInvocations(storage);
        mvc.perform(get("/api/travel/public/login-map-preview")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/travel/public/login-map-preview/markers/1/thumbnail")).andExpect(status().isNotFound());
        verifyNoInteractions(storage, redis, coordinator);
    }

    @Test void createIsWritableReadsAreReadOnlyAndPersistenceAndTokenAdapterRequireAnExistingTransaction() {
        var f = fixture();
        assertThatThrownBy(() -> store.active("missing")).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> runtime.now()).isInstanceOf(IllegalTransactionStateException.class);
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse(); return call.callRealMethod(); })
                .when(target).save(any(), any(), any(), any(), any());
        var link = create(f);
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue(); return call.callRealMethod(); })
                .when(target).active(link.token());
        useCase.map(link.token()); useCase.preview(link.token()); useCase.media(link.token(), f.photo());
        verifyNoInteractions(storage, redis, coordinator);
    }

    Link create(Fixture f) { return useCase.create(f.owner(), new Draft("Synthetic link", List.of(f.plan()), null, null, null)); }
    Fixture fixture() {
        return new TransactionTemplate(transactions).execute(tx -> {
            var user = new AppUser(); user.setLoginId("link-" + UUID.randomUUID()); user.setDisplayName("Synthetic owner"); user.setPasswordHash("unused-test-hash"); users.save(user);
            var plan = new TravelPlan(); plan.setOwner(user); plan.setName("Synthetic trip"); plan.setStatus(TravelPlanStatus.PLANNED); plan.setPublicShared(false);
            plan.setStartDate(LocalDate.of(2026, 10, 16)); plan.setEndDate(LocalDate.of(2026, 10, 17)); plans.save(plan);
            var first = record(plan, TravelRecordType.MEMORY, "37.5"); var second = record(plan, TravelRecordType.MEMORY, "37.6"); var ledger = record(plan, TravelRecordType.LEDGER, "37.7");
            long photo = asset(plan, first, TravelMediaType.PHOTO), extra = asset(plan, first, TravelMediaType.PHOTO);
            long other = asset(plan, second, TravelMediaType.PHOTO), ledgerPhoto = asset(plan, ledger, TravelMediaType.PHOTO), receipt = asset(plan, first, TravelMediaType.RECEIPT);
            var route = new TravelRouteSegment(); route.setPlan(plan); route.setRouteDate(plan.getStartDate()); route.setTitle("Synthetic route");
            route.setRoutePathJson("[{\"latitude\":37.5,\"longitude\":127,\"label\":\"Private label\",\"linkedMemoryId\":" + first.getId() + "},{\"latitude\":37.6,\"longitude\":127}]");
            routes.save(route);
            return new Fixture(user.getId(), plan.getId(), first.getId(), photo, extra, second.getId(), other, ledgerPhoto, receipt, route.getId());
        });
    }
    TravelExpenseRecord record(TravelPlan plan, TravelRecordType type, String latitude) {
        var record = new TravelExpenseRecord(); record.setPlan(plan); record.setRecordType(type); record.setTitle("Synthetic place");
        record.setCategory("Place");
        record.setExpenseDate(plan.getStartDate()); record.setExpenseTime(LocalTime.NOON); record.setAmount(BigDecimal.ZERO);
        record.setLatitude(new BigDecimal(latitude)); record.setLongitude(new BigDecimal("127")); return records.save(record);
    }
    long asset(TravelPlan plan, TravelExpenseRecord record, TravelMediaType type) {
        var asset = new TravelMediaAsset(); asset.setPlan(plan); asset.setRecord(record); asset.setUploadedBy(plan.getOwner()); asset.setMediaType(type);
        asset.setOriginalFileName("synthetic.jpg"); asset.setStoredFileName("synthetic.jpg"); asset.setStoragePath("test-only/map-link/" + UUID.randomUUID());
        asset.setContentType("image/jpeg"); asset.setFileSize(3L); return assets.save(asset).getId();
    }
    String path(long media) { return assets.findById(media).orElseThrow().getStoragePath(); }
    AppUserPrincipal principal(Fixture f) { return AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()); }
}
