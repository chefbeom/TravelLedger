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
import com.playdata.calen.travel.adapter.out.cache.OwnedTravelMapCache.VersionedMapOverview;
import com.playdata.calen.travel.adapter.out.persistence.JpaOwnedTravelMapReadAdapter;
import com.playdata.calen.travel.application.port.in.*;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.*;
import com.playdata.calen.travel.domain.model.TravelRouteData;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:travel-owned-map-reads;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelOwnedMapReadRefactoringIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired TravelMediaAssetRepository assets;
    @Autowired TravelRouteSegmentRepository routes;
    @Autowired ReadOwnedTravelMapUseCase reads;
    @Autowired RefreshTravelPhotoClustersUseCase projection;
    @Autowired TravelRoutePayloadPort payload;
    @Autowired TravelService legacy;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean JpaOwnedTravelMapReadAdapter store;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean TravelClusterRefreshCoordinator coordinator;
    JpaOwnedTravelMapReadAdapter storeTarget;
    record Fixture(long owner, long plan, long record, long photo) {}

    @BeforeEach void unwrapPersistenceSpy() {
        // Stub/verify the spy itself, not the MANDATORY transaction proxy around it.
        storeTarget = AopTestUtils.getUltimateTargetObject(store);
        reset(storeTarget);
    }

    @Test void allThreeHttpReadsStillRequireAuthentication() throws Exception {
        for (String path : List.of("/api/travel/my-map", "/api/travel/my-map/markers/1", "/api/travel/photo-frame-media")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(redis, storage, coordinator);
    }

    @Test void overviewHttpUsesTheAsyncExecutorAndKeepsProjectionSnapshotRouteAndLegacyFacadeJson() throws Exception {
        var f = fixture(); addRecord(f, TravelRecordType.LEDGER, "Ledger", "37.5000", "127.0000");
        route(f); projection.apply(f.owner(), projection.compute(f.owner()));
        when(coordinator.ensureCurrent(f.owner())).thenReturn(17L);
        var request = mvc.perform(get("/api/travel/my-map").with(user(principal(f))))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mvc.perform(asyncDispatch(request)).andExpect(status().isOk())
                .andExpect(jsonPath("$.includedPlanCount").value(1)).andExpect(jsonPath("$.markerCount").value(1))
                .andExpect(jsonPath("$.photoMarkerCount").value(1)).andExpect(jsonPath("$.photoClusterCount").value(1))
                .andExpect(jsonPath("$.markers[0].planColorHex").value("#ABCDEF"))
                .andExpect(jsonPath("$.markers[0].photoUrl").value("/api/travel/media/" + f.photo() + "/content"))
                .andExpect(jsonPath("$.photoPins[0].mediaId").value(f.photo()))
                .andExpect(jsonPath("$.routes[0].sourceType").value("GPX"))
                .andExpect(jsonPath("$.routes[0].points[0].linkedMemoryId").value(f.record())).andReturn();
        assertThat(json.readTree(response.getResponse().getContentAsByteArray()))
                .isEqualTo(json.readTree(json.writeValueAsString(legacy.getMyMapOverview(f.owner()))));
        assertThat(reads.overview(f.owner()).totalDistanceKm()).isEqualTo(new BigDecimal("1.235"));
        verifyNoInteractions(storage);
    }

    @Test void clusterWaitSuspendsOuterTransactionWhileOverviewCanWriteAndRestoresTheCaller() {
        var f = fixture();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return 23L;
        }).when(coordinator).ensureCurrent(f.owner());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            plans.findById(f.plan()).orElseThrow().setName("Committed in overview transaction");
            return call.callRealMethod();
        }).when(storeTarget).planCount(f.owner());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            reads.overview(f.owner());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            tx.setRollbackOnly();
        });
        assertThat(plans.findById(f.plan()).orElseThrow().getName()).isEqualTo("Committed in overview transaction");
        verifyNoInteractions(storage);
    }

    @Test void exactGenerationUsesTheOldEnvelopeAndAnInactiveAccountCannotReadIt() {
        var f = fixture(); when(coordinator.ensureCurrent(f.owner())).thenReturn(29L);
        legacy.getMyMapOverview(f.owner());
        var envelope = ArgumentCaptor.forClass(Object.class);
        verify(redis).set(eq("travel:mymap:overview:" + f.owner()), envelope.capture(), eq(Duration.ofSeconds(60)));
        var cached = (VersionedMapOverview) envelope.getValue();
        assertThat(cached.generation()).isEqualTo(29);
        when(redis.get("travel:mymap:overview:" + f.owner(), VersionedMapOverview.class)).thenReturn(cached);
        clearInvocations(storeTarget);
        assertThat(reads.overview(f.owner()).markers().get(0).marker().id()).isEqualTo(f.record());
        verify(storeTarget, never()).planCount(any());
        when(coordinator.ensureCurrent(f.owner())).thenReturn(30L);
        reads.overview(f.owner()); verify(storeTarget).planCount(f.owner());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> users.findById(f.owner()).orElseThrow().setActive(false));
        clearInvocations(redis);
        assertThatThrownBy(() -> reads.overview(f.owner())).hasMessage("사용자 계정을 찾을 수 없습니다.");
        verify(redis, never()).get(anyString(), any(Class.class));
    }

    @Test void nearestBundleKeepsSelectedFirstTenOwnerMemoryNeighborsAndPhotoOnlyDetails() throws Exception {
        var f = fixture(); var other = fixture();
        for (int i = 1; i <= 12; i++) addRecord(f, TravelRecordType.MEMORY, "Near " + i, "37.500" + i, "127.0000");
        long ledger = addRecord(f, TravelRecordType.LEDGER, "Not memory", "37.5000", "127.0000");
        addAsset(f, f.record(), TravelMediaType.RECEIPT);
        var result = reads.markerBundle(f.owner(), f.record());
        assertThat(result.markers()).hasSize(11);
        assertThat(result.markers().get(0).marker().id()).isEqualTo(f.record());
        assertThat(result.markers()).extracting(detail -> detail.marker().id()).doesNotContain(ledger, other.record());
        assertThat(result.markers().get(0).photos()).extracting(photo -> photo.id()).containsExactly(f.photo());
        mvc.perform(get("/api/travel/my-map/markers/{id}", f.record()).with(user(principal(f))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selectedMarkerId").value(f.record()))
                .andExpect(jsonPath("$.markers.length()").value(11))
                .andExpect(jsonPath("$.markers[0].photoCount").value(1))
                .andExpect(jsonPath("$.markers[0].mediaItems[0].contentUrl").value("/api/travel/media/" + f.photo() + "/content"));
        verifyNoInteractions(storage, redis, coordinator);
    }

    @Test void foreignAndNonMemoryOrMissingCoordinatesStayRejectedBeforeBundleHydration() throws Exception {
        var f = fixture(); var other = fixture();
        long ledger = addRecord(f, TravelRecordType.LEDGER, "Ledger", "37.5", "127");
        long noCoordinates = addRecord(f, TravelRecordType.MEMORY, "No coordinates", null, null);
        for (long id : List.of(other.record(), ledger, Long.MAX_VALUE)) {
            mvc.perform(get("/api/travel/my-map/markers/{id}", id).with(user(principal(f)))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/travel/my-map/markers/{id}", noCoordinates).with(user(principal(f)))).andExpect(status().isBadRequest());
        verify(storeTarget, never()).details(anyList()); verifyNoInteractions(storage, redis, coordinator);
    }

    @Test void framesIncludeLedgerPhotosButNotReceiptsOrAnotherOwnerAndKeepAllMetadata() throws Exception {
        var f = fixture(); var other = fixture();
        long ledger = addRecord(f, TravelRecordType.LEDGER, "Ledger photo", "37.5", "127");
        long photo = addAsset(f, ledger, TravelMediaType.PHOTO); addAsset(f, f.record(), TravelMediaType.RECEIPT);
        var result = reads.frames(f.owner());
        assertThat(result).extracting(Frame::id).containsExactly(photo, f.photo()).doesNotContain(other.photo());
        var frame = result.get(0);
        assertThat(frame.recordType()).isEqualTo(TravelRecordType.LEDGER);
        assertThat(frame.gpsLatitude()).isEqualByComparingTo("35.1");
        assertThat(frame.gpsLongitude()).isEqualByComparingTo("135.2");
        assertThat(frame.caption()).isEqualTo("Caption"); assertThat(frame.uploadedAt()).isNotNull();
        mvc.perform(get("/api/travel/photo-frame-media").with(user(principal(f)))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recordType").value("LEDGER"))
                .andExpect(jsonPath("$[0].contentUrl").value("/api/travel/media/" + photo + "/content"))
                .andExpect(jsonPath("$[0].expenseDate").value("2026-10-16")).andExpect(jsonPath("$[0].title").value("Ledger photo"));
        verifyNoInteractions(storage, redis, coordinator);
    }

    @Test void storeRequiresATransactionAndBothSmallReadsRunReadOnly() {
        var f = fixture();
        assertThatThrownBy(() -> store.frames(f.owner())).isInstanceOf(IllegalTransactionStateException.class);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return call.callRealMethod();
        }).when(storeTarget).requireActiveOwner(f.owner());
        reads.frames(f.owner()); reads.markerBundle(f.owner(), f.record());
        verifyNoInteractions(storage, redis, coordinator);
    }

    Fixture fixture() {
        return new TransactionTemplate(transactions).execute(tx -> {
            var user = new AppUser(); user.setLoginId("map-" + UUID.randomUUID()); user.setDisplayName("Synthetic owner"); user.setPasswordHash("unused-test-hash"); users.save(user);
            var plan = new TravelPlan(); plan.setOwner(user); plan.setName("Synthetic trip"); plan.setColorHex("#abcdef");
            plan.setStartDate(LocalDate.of(2026, 10, 16)); plan.setEndDate(LocalDate.of(2026, 10, 17)); plans.save(plan);
            var f = new Fixture(user.getId(), plan.getId(), 0, 0);
            long record = addRecord(f, TravelRecordType.MEMORY, "Synthetic place", "37.5000", "127.0000");
            return new Fixture(user.getId(), plan.getId(), record, addAsset(f, record, TravelMediaType.PHOTO));
        });
    }
    long addRecord(Fixture f, TravelRecordType type, String title, String latitude, String longitude) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var record = new TravelExpenseRecord(); record.setPlan(plans.getReferenceById(f.plan())); record.setRecordType(type); record.setTitle(title);
            record.setExpenseDate(LocalDate.of(2026, 10, 16)); record.setExpenseTime(LocalTime.of(12, 34)); record.setCategory("Place");
            record.setCountry("KR"); record.setRegion("Seoul"); record.setPlaceName("Place"); record.setMemo("Memo"); record.setAmount(BigDecimal.ZERO);
            record.setLatitude(latitude == null ? null : new BigDecimal(latitude)); record.setLongitude(longitude == null ? null : new BigDecimal(longitude));
            return records.save(record).getId();
        });
    }
    long addAsset(Fixture f, long record, TravelMediaType type) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var asset = new TravelMediaAsset(); asset.setPlan(plans.getReferenceById(f.plan())); asset.setRecord(records.getReferenceById(record)); asset.setUploadedBy(users.getReferenceById(f.owner()));
            asset.setMediaType(type); asset.setOriginalFileName("photo.jpg"); asset.setStoredFileName("photo.jpg"); asset.setStoragePath("test-only/map/" + UUID.randomUUID());
            asset.setCaption("Caption"); asset.setContentType("image/jpeg"); asset.setFileSize(3L); asset.setGpsLatitude(new BigDecimal("35.1")); asset.setGpsLongitude(new BigDecimal("135.2"));
            return assets.save(asset).getId();
        });
    }
    void route(Fixture f) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var route = new TravelRouteSegment(); route.setPlan(plans.getReferenceById(f.plan())); route.setRouteDate(LocalDate.of(2026, 10, 16)); route.setTitle("GPX route");
            route.setSourceType(TravelRouteSourceType.GPX); route.setDurationMinutes(null); route.setDistanceKm(new BigDecimal("1.235")); route.setLineColorHex(null);
            route.setRoutePathJson(payload.encode(List.of(new TravelRouteData.Point(new BigDecimal("37.5"), new BigDecimal("127"), "MEMORY", f.record(), "Start")), TravelRouteSourceType.MANUAL));
            route.setGpxFilesJson("[{\"originalFileName\":\"not-opened.gpx\",\"storagePath\":\"test-only/never-opened\",\"contentType\":\"application/gpx+xml\",\"fileSize\":3}]");
            routes.save(route);
        });
    }
    AppUserPrincipal principal(Fixture f) { return AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()); }
}
