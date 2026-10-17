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
import com.playdata.calen.travel.adapter.out.persistence.JpaTravelAtlasAdapter;
import com.playdata.calen.travel.application.port.in.ReadTravelAtlasUseCase;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:travel-atlas-reads;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false",
        "app.security.public-media-key=isolated-atlas-test-key-not-production"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelAtlasRefactoringIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired TravelMediaAssetRepository assets;
    @Autowired TravelRouteSegmentRepository routes;
    @Autowired TravelPlanShareRepository shares;
    @Autowired ReadTravelAtlasUseCase reads;
    @Autowired TravelPublicMediaTokenService tokens;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @MockitoSpyBean JpaTravelAtlasAdapter store;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelClusterRefreshCoordinator coordinator;
    JpaTravelAtlasAdapter target;
    record Fixture(long owner, long plan, long memory, long photo, long extra, long gpsMemory, long gpsPhoto, long ledgerPhoto, long receipt, long route) {}
    @BeforeEach void unwrapAndIsolateGlobalPublicationFixtures() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:travel-atlas-reads");
        }
        target = AopTestUtils.getUltimateTargetObject(store); reset(target);
        // Publication/community are global surfaces, unlike owner-scoped fixtures.
        // Keep previous H2 rows but hide their exposure before constructing this test's fixtures.
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            plans.findAll().forEach(plan -> plan.setPublicShared(false));
            records.findAll().forEach(record -> record.setSharedWithCommunity(false));
        });
    }

    @Test void allThreeReadRoutesRequireAuthentication() throws Exception {
        for (String path : List.of("/api/travel/public-trips", "/api/travel/public-trips/photo-clusters/1", "/api/travel/community-feed")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(storage, redis, coordinator);
    }

    @Test void publishedPlansWinOverRecipientDuplicatesAndPrivatePhotosUseTheCurrentRecipientShare() throws Exception {
        var published = fixture(TravelPlanStatus.COMPLETED, true); var received = fixture(TravelPlanStatus.COMPLETED, false);
        var incomplete = fixture(TravelPlanStatus.PLANNED, true); var viewer = fixture(TravelPlanStatus.PLANNED, false);
        long privateShare = share(received, viewer); share(published, viewer);
        var response = mvc.perform(get("/api/travel/public-trips").with(user(principal(viewer)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.includedPlanCount").value(2)).andExpect(jsonPath("$.photoMarkerCount").value(6))
                .andExpect(jsonPath("$.plans[0].planId").value(published.plan())).andExpect(jsonPath("$.plans[0].viewerShareId").isEmpty())
                .andExpect(jsonPath("$.plans[0].shareVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.plans[1].planId").value(received.plan())).andExpect(jsonPath("$.plans[1].viewerShareId").value(privateShare))
                .andExpect(jsonPath("$.plans[1].shareVisibility").value("RESTRICTED"))
                .andExpect(jsonPath("$.plans[1].memoryRecordCount").value(2)).andExpect(jsonPath("$.plans[1].mediaItemCount").value(3))
                .andExpect(jsonPath("$.routes[0].durationMinutes").value(0)).andReturn();
        var tree = json.readTree(response.getResponse().getContentAsByteArray());
        assertThat(tree.toString()).doesNotContain("\"planId\":" + incomplete.plan() + ",");
        var privateUrl = tree.get("plans").get(1).get("representativePhotoUrl").asText();
        assertThat(privateUrl).startsWith("/api/travel/shared-exhibits/" + privateShare + "/media/").doesNotContain("?token=");
        var publicUrl = tree.get("plans").get(0).get("representativePhotoUrl").asText();
        assertThat(publicUrl).startsWith("/api/travel/public/media/").contains("?token=");
        long publicMedia = Long.parseLong(publicUrl.split("/media/")[1].split("/")[0]);
        assertThat(tokens.matches(publicMedia, publicUrl.substring(publicUrl.indexOf("?token=") + 7))).isTrue();
        verifyNoInteractions(storage, redis, coordinator);
    }

    @Test void privateAtlasAndItsOldClusterAndMediaAddressesStopWorkingAfterTheShareIsDeleted() throws Exception {
        var source = fixture(TravelPlanStatus.COMPLETED, false); var viewer = fixture(TravelPlanStatus.PLANNED, false);
        var stranger = fixture(TravelPlanStatus.PLANNED, false); long share = share(source, viewer);
        assertThat(reads.overview(stranger.owner()).plans()).isEmpty();
        long cluster = reads.overview(viewer.owner()).clusters().get(0).value().id();
        var url = "/api/travel/shared-exhibits/" + share + "/media/" + source.photo() + "/content";
        when(storage.loadAsResource(path(source.photo()))).thenReturn(new ByteArrayResource(new byte[] {1, 2}));
        mvc.perform(get(url).with(user(principal(viewer)))).andExpect(status().isOk()).andExpect(content().bytes(new byte[] {1, 2}));
        clearInvocations(storage);
        mvc.perform(get(url).with(user(principal(stranger)))).andExpect(status().isNotFound());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> shares.deleteById(share));
        assertThat(reads.overview(viewer.owner()).plans()).isEmpty();
        mvc.perform(get("/api/travel/public-trips/photo-clusters/{id}", cluster).with(user(principal(viewer)))).andExpect(status().isNotFound());
        mvc.perform(get(url).with(user(principal(viewer)))).andExpect(status().isNotFound());
        verifyNoInteractions(storage, redis, coordinator);
    }

    @Test void publicationAndCompletionChangesRevokePublicAtlasWhileCommunityMemoryRemainsADistinctSurface() throws Exception {
        var source = fixture(TravelPlanStatus.COMPLETED, true); var viewer = fixture(TravelPlanStatus.PLANNED, false);
        String url = "/api/travel/public/media/" + source.photo() + "/content?token=" + tokens.issueToken(source.photo());
        when(storage.loadAsResource(path(source.photo()))).thenReturn(new ByteArrayResource(new byte[] {1}));
        mvc.perform(get(url)).andExpect(status().isOk());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> plans.findById(source.plan()).orElseThrow().setPublicShared(false));
        assertThat(reads.overview(viewer.owner()).plans()).isEmpty(); mvc.perform(get(url)).andExpect(status().isNotFound());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { var plan = plans.findById(source.plan()).orElseThrow(); plan.setPublicShared(true); plan.setStatus(TravelPlanStatus.PLANNED); });
        assertThat(reads.overview(viewer.owner()).plans()).isEmpty(); mvc.perform(get(url)).andExpect(status().isNotFound());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> records.findById(source.memory()).orElseThrow().setSharedWithCommunity(true));
        var feed = reads.community(viewer.owner(), null, null);
        assertThat(feed.items()).hasSize(1); assertThat(feed.items().get(0).hero().uploadedBy()).isEqualTo("Different uploader");
        assertThat(reads.overview(viewer.owner()).plans()).isEmpty(); mvc.perform(get(url)).andExpect(status().isOk());
        verifyNoInteractions(redis, coordinator);
    }

    @Test void clusterUsesRepresentativeRatherThanCentroidCoordinatesAndKeepsFocusPagingAndThirtySixItemCap() throws Exception {
        var source = fixture(TravelPlanStatus.COMPLETED, true); var viewer = fixture(TravelPlanStatus.PLANNED, false);
        var cluster = reads.overview(viewer.owner()).clusters().stream().filter(c -> c.value().representativeMediaId().equals(source.extra())).findFirst().orElseThrow();
        mvc.perform(get("/api/travel/public-trips/photo-clusters/{id}", cluster.value().id()).with(user(principal(viewer))).param("size", "1").param("focusMediaId", String.valueOf(source.photo())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.latitude").value(37.50002)).andExpect(jsonPath("$.longitude").value(127.00002))
                .andExpect(jsonPath("$.totalPhotoCount").value(2)).andExpect(jsonPath("$.representativeMediaId").value(source.extra()))
                .andExpect(jsonPath("$.photos[0].id").value(source.photo())).andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/travel/public-trips/photo-clusters/{id}", cluster.value().id()).with(user(principal(viewer))).param("size", "100").param("page", "-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(36)).andExpect(jsonPath("$.page").value(0));
        verifyNoInteractions(storage, redis, coordinator);
    }

    @Test void communitySqlTotalsRequireMemoryAndPhotoButNotCompletedPlanOrCoordinatesAndAuthorComesFromUploader() throws Exception {
        var source = fixture(TravelPlanStatus.PLANNED, false); var viewer = fixture(TravelPlanStatus.PLANNED, false);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            records.findById(source.memory()).orElseThrow().setSharedWithCommunity(true);
            records.findById(source.gpsMemory()).orElseThrow().setSharedWithCommunity(true);
            assets.findById(source.ledgerPhoto()).orElseThrow().getRecord().setSharedWithCommunity(true);
            var noPhoto = memory(plans.getReferenceById(source.plan()), TravelRecordType.MEMORY, null);
            noPhoto.setSharedWithCommunity(true);
        });
        mvc.perform(get("/api/travel/community-feed").with(user(principal(viewer))).param("size", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].memoryId").value(source.gpsMemory())).andExpect(jsonPath("$.items[0].latitude").isEmpty())
                .andExpect(jsonPath("$.items[0].ownerDisplayName").value("Different uploader"));
        mvc.perform(get("/api/travel/community-feed").with(user(principal(viewer))).param("page", "-2").param("size", "999")).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.totalElements").value(2));
        verifyNoInteractions(storage, redis, coordinator);
    }

    @Test void atlasRoutesStillReadOriginalGpxAndUseFullDetailDefaultsInsteadOfOwnedMapSnapshotDefaults() {
        var source = fixture(TravelPlanStatus.COMPLETED, true); var viewer = fixture(TravelPlanStatus.PLANNED, false);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var route = routes.findById(source.route()).orElseThrow(); route.setSourceType(TravelRouteSourceType.GPX);
            route.setGpxFilesJson("[{\"originalFileName\":\"route.gpx\",\"storagePath\":\"test-only/atlas.gpx\",\"contentType\":\"application/gpx+xml\",\"fileSize\":3}]");
        });
        when(storage.loadAsResource("test-only/atlas.gpx")).thenReturn(new ByteArrayResource(
                "<gpx><trk><trkseg><trkpt lat=\"35.123456\" lon=\"139.987654\"/><trkpt lat=\"35.223456\" lon=\"139.887654\"/></trkseg></trk></gpx>".getBytes(StandardCharsets.UTF_8)));
        var route = reads.overview(viewer.owner()).routes().get(0);
        assertThat(route.points().get(0).latitude()).isEqualByComparingTo("35.123456");
        assertThat(route.durationMinutes()).isZero(); assertThat(route.lineColorHex()).isEqualTo("#ABCDEF");
        verify(storage).loadAsResource("test-only/atlas.gpx"); verifyNoInteractions(redis, coordinator);
    }

    @Test void allReadsAreReadOnlyMandatoryAndInactiveViewersFailBeforeChildOrTokenWork() {
        var source = fixture(TravelPlanStatus.COMPLETED, true); var viewer = fixture(TravelPlanStatus.PLANNED, false);
        assertThatThrownBy(() -> store.publishedCompletedPlans()).isInstanceOf(IllegalTransactionStateException.class);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue(); return call.callRealMethod();
        }).when(target).requireActiveViewer(viewer.owner());
        var atlas = reads.overview(viewer.owner()); reads.cluster(viewer.owner(), atlas.clusters().get(0).value().id(), 0, 12, null); reads.community(viewer.owner(), null, null);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> users.findById(viewer.owner()).orElseThrow().setActive(false));
        clearInvocations(target);
        assertThatThrownBy(() -> reads.overview(viewer.owner())).hasMessage("사용자 계정을 찾을 수 없습니다.");
        verify(target, never()).publishedCompletedPlans(); verifyNoInteractions(storage, redis, coordinator);
    }

    Fixture fixture(TravelPlanStatus status, boolean published) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var owner = account("Plan owner"); var uploader = account("Different uploader");
            var plan = new TravelPlan(); plan.setOwner(owner); plan.setName("Synthetic trip"); plan.setColorHex("#abcdef"); plan.setStatus(status); plan.setPublicShared(published);
            plan.setPublicSharedAt(LocalDateTime.of(2026, 10, 16, 1, 2)); plan.setStartDate(LocalDate.of(2026, 10, 16)); plan.setEndDate(plan.getStartDate().plusDays(1)); plans.save(plan);
            var first = memory(plan, TravelRecordType.MEMORY, "37.5"); var second = memory(plan, TravelRecordType.MEMORY, null); var ledger = memory(plan, TravelRecordType.LEDGER, "37.7");
            long photo = asset(plan, first, uploader, TravelMediaType.PHOTO, null, null, false);
            long extra = asset(plan, first, uploader, TravelMediaType.PHOTO, "37.50002", "127.00002", true);
            long gps = asset(plan, second, uploader, TravelMediaType.PHOTO, "36.1234567", "126.1234567", false);
            long ledgerPhoto = asset(plan, ledger, uploader, TravelMediaType.PHOTO, null, null, false);
            long receipt = asset(plan, first, uploader, TravelMediaType.RECEIPT, null, null, false);
            var route = new TravelRouteSegment(); route.setPlan(plan); route.setRouteDate(plan.getStartDate()); route.setTitle("Route"); route.setDurationMinutes(null);
            route.setRoutePathJson("[{\"latitude\":37.5,\"longitude\":127},{\"latitude\":37.6,\"longitude\":127.1}]"); routes.save(route);
            return new Fixture(owner.getId(), plan.getId(), first.getId(), photo, extra, second.getId(), gps, ledgerPhoto, receipt, route.getId());
        });
    }
    AppUser account(String display) {
        var user = new AppUser(); user.setLoginId("atlas-" + UUID.randomUUID()); user.setDisplayName(display); user.setPasswordHash("unused-test-hash"); return users.save(user);
    }
    TravelExpenseRecord memory(TravelPlan plan, TravelRecordType type, String latitude) {
        var record = new TravelExpenseRecord(); record.setPlan(plan); record.setRecordType(type); record.setTitle("Synthetic memory"); record.setCategory("Place");
        record.setExpenseDate(plan.getStartDate()); record.setExpenseTime(LocalTime.NOON); record.setAmount(BigDecimal.ZERO);
        record.setLatitude(latitude == null ? null : new BigDecimal(latitude)); record.setLongitude(latitude == null ? null : new BigDecimal("127")); return records.save(record);
    }
    long asset(TravelPlan plan, TravelExpenseRecord record, AppUser uploader, TravelMediaType type, String lat, String lon, boolean representative) {
        var asset = new TravelMediaAsset(); asset.setPlan(plan); asset.setRecord(record); asset.setUploadedBy(uploader); asset.setMediaType(type);
        asset.setOriginalFileName("photo.jpg"); asset.setStoredFileName("photo.jpg"); asset.setStoragePath("test-only/atlas/" + UUID.randomUUID()); asset.setContentType("image/jpeg"); asset.setFileSize(3L);
        asset.setGpsLatitude(lat == null ? null : new BigDecimal(lat)); asset.setGpsLongitude(lon == null ? null : new BigDecimal(lon)); asset.setRepresentativeOverride(representative);
        return assets.save(asset).getId();
    }
    long share(Fixture source, Fixture viewer) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var share = new TravelPlanShare(); share.setPlan(plans.getReferenceById(source.plan())); share.setSharedBy(users.getReferenceById(source.owner()));
            share.setRecipient(users.getReferenceById(viewer.owner())); share.setCreatedAt(LocalDateTime.now()); return shares.save(share).getId();
        });
    }
    String path(long id) { return assets.findById(id).orElseThrow().getStoragePath(); }
    AppUserPrincipal principal(Fixture f) { return AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()); }
}
