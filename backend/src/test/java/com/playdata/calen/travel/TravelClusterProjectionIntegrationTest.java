package com.playdata.calen.travel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache;
import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.*;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:travel-cluster-projection-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelClusterProjectionIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired TravelMediaAssetRepository media;
    @Autowired TravelPhotoClusterRepository clusters;
    @Autowired TravelPhotoClusterMemberRepository members;
    @Autowired RefreshTravelPhotoClustersUseCase projection;
    @Autowired com.playdata.calen.travel.application.port.in.ManageTravelPhotoClustersUseCase details;
    @MockitoSpyBean com.playdata.calen.travel.adapter.out.persistence.JpaTravelClusterDetailAdapter detailStore;
    @Autowired TravelClusterProjectionStore store;
    @Autowired TravelClusterSnapshotCachePort snapshots;
    @Autowired TravelService legacy;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean TravelClusterRefreshCoordinator coordinator;
    @MockitoSpyBean TravelPhotoClusterPolicy policy;
    private record Fixture(long owner, long plan, long first, long second) {}

    @Test void cpuCalculationUsesDetachedValuesOutsideBothItsReadTransactionAndTheCallersTransaction() {
        var f = fixture();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(policy).cluster(anyList());
        var result = projection.compute(f.owner());
        assertThat(result).hasSize(1); assertThat(result.get(0).id()).isEqualTo(f.first());
        assertThat(result.get(0).representative().mediaId()).isEqualTo(f.second());
        assertThat(result.get(0).photoCount()).isEqualTo(2);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(projection.compute(f.owner())).isEqualTo(result);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
        assertThat(clusters.findAllByOwnerIdOrderByMemoryDateDescMemoryTimeDescIdDesc(f.owner())).isEmpty();
        verifyNoInteractions(redis, storage, coordinator);
    }

    @Test void projectionPreservesStableIdsAndAvoidsReplacingUnchangedMemberships() {
        var f = fixture(); var computed = projection.compute(f.owner()); projection.apply(f.owner(), computed);
        var original = clusters.findByIdAndOwnerId(f.first(), f.owner()).orElseThrow();
        var memberIds = memberIds(f.owner());
        projection.apply(f.owner(), computed);
        assertThat(clusters.findByIdAndOwnerId(f.first(), f.owner()).orElseThrow().getUpdatedAt()).isEqualTo(original.getUpdatedAt());
        assertThat(memberIds(f.owner())).isEqualTo(memberIds);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var asset = media.findById(f.second()).orElseThrow(); asset.getRecord().setTitle("Updated place");
        });
        projection.apply(f.owner(), projection.compute(f.owner()));
        assertThat(clusters.findByIdAndOwnerId(f.first(), f.owner()).orElseThrow().getTitle()).isEqualTo("Updated place");
        assertThat(memberIds(f.owner())).isEqualTo(memberIds);
        var snapshot = projection.stored(f.owner());
        assertThat(snapshot.photoMarkerCount()).isEqualTo(2); assertThat(snapshot.photoClusterCount()).isEqualTo(1);
        assertThat(snapshot.pins()).extracting(Pin::mediaId).containsExactly(f.second(), f.first());
        assertThat(snapshot.summaries().get(0).planColorHex()).isEqualTo("#ABCDEF");
        // Existing map entry point loads this same projection and emits the old DTO contract.
        var overview = legacy.getMyMapOverview(f.owner());
        assertThat(overview.photoClusters().get(0).representativeMediaId()).isEqualTo(f.second());
        assertThat(overview.photoPins()).extracting(p -> p.photoUrl()).containsExactly(
                "/api/travel/media/"+f.second()+"/content", "/api/travel/media/"+f.first()+"/content");
    }

    @Test void sqlDeletionRollsBackProjectionAndCommitEvictsCachesInTheExistingOrderWithoutTouchingAnotherOwner() {
        var f = fixture(); var other = fixture();
        projection.apply(f.owner(), projection.compute(f.owner())); projection.apply(other.owner(), projection.compute(other.owner()));
        clearInvocations(redis);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            projection.apply(f.owner(), List.of());
            assertThat(clusters.findByIdAndOwnerId(f.first(), f.owner())).isEmpty();
            throw new IllegalStateException("failure after projection SQL delete");
        })).hasMessage("failure after projection SQL delete");
        assertThat(projection.stored(f.owner()).photoMarkerCount()).isEqualTo(2);
        verify(redis).delete(snapshotKey(f.owner())); verifySummaryEviction(f.owner(), 1);
        clearInvocations(redis);
        projection.apply(f.owner(), List.of());
        assertThat(projection.stored(f.owner())).isNull(); assertThat(memberIds(f.owner())).isEmpty();
        assertThat(projection.stored(other.owner()).photoMarkerCount()).isEqualTo(2);
        var order = inOrder(redis);
        order.verify(redis).delete(snapshotKey(f.owner()));
        order.verify(redis, times(2)).delete(TravelSummaryCache.plansKey(f.owner()), TravelSummaryCache.portfolioKey(f.owner()), TravelSummaryCache.mapKey(f.owner()), TravelSummaryCache.PUBLIC_TRIPS_KEY);
        order.verify(redis).delete(snapshotKey(f.owner()));
        verifyNoInteractions(storage, coordinator);
    }

    @Test void manualRepresentativeApiUsesTheIndependentSynchronousRefreshAndKeepsOwnershipRules() throws Exception {
        var f = fixture(); var other = fixture(); projection.apply(f.owner(), projection.compute(f.owner())); clearInvocations(redis);
        mvc.perform(put("/api/travel/my-map/photo-clusters/{id}/representative", f.first())
                .with(user(AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":"+f.first()+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.representativeMediaId").value(f.first()))
                .andExpect(jsonPath("$.id").value(f.first()));
        assertThat(projection.stored(f.owner()).summaries().get(0).representativeMediaId()).isEqualTo(f.first());
        verify(coordinator).lockManualRefresh(f.owner()); verify(coordinator).dispatch(f.owner());
        mvc.perform(put("/api/travel/my-map/photo-clusters/{id}/representative", f.first())
                .with(user(AppUserPrincipal.from(users.findById(other.owner()).orElseThrow()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":"+f.first()+"}"))
                .andExpect(status().isNotFound());
        assertThat(projection.stored(f.owner()).summaries().get(0).representativeMediaId()).isEqualTo(f.first());
    }

    @Test void pointQueryKeepsOwnerPhotoMemoryAndCoordinateFiltersAndGpsPrecedence() {
        var f = fixture(); var other = fixture();
        long gps = photo(record(f.plan(), TravelRecordType.MEMORY, "37.5", "127"), TravelMediaType.PHOTO, "38.1", "128.2", false);
        photo(record(f.plan(), TravelRecordType.MEMORY, null, null), TravelMediaType.PHOTO, null, null, false);
        photo(record(f.plan(), TravelRecordType.LEDGER, "37.5", "127"), TravelMediaType.PHOTO, null, null, false);
        photo(record(f.plan(), null, "37.5", "127"), TravelMediaType.PHOTO, null, null, false);
        photo(record(f.plan(), TravelRecordType.MEMORY, "37.5", "127"), TravelMediaType.RECEIPT, null, null, false);
        var points = store.loadPhotoPoints(f.owner());
        assertThat(points).extracting(PhotoPoint::mediaId).containsExactly(gps, f.second(), f.first());
        assertThat(points.get(0).latitude()).isEqualByComparingTo("38.1"); assertThat(points.get(0).longitude()).isEqualByComparingTo("128.2");
        assertThat(points).allMatch(p -> p.planId().equals(f.plan()));
        assertThat(store.loadPhotoPoints(other.owner())).hasSize(2);
    }

    @Test void projectionWritesAndSnapshotCacheRequireTheEnclosingUseCaseTransaction() {
        assertThatThrownBy(() -> store.save(7L, List.of())).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> store.loadSnapshot(7L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> snapshots.invalidateNow(7L)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void ownedDetailApiKeepsAsyncPagingFocusAndContentUrls() throws Exception {
        var f=fixture(); projection.apply(f.owner(),projection.compute(f.owner()));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(coordinator).ensureCurrent(f.owner());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return call.callRealMethod();
        }).when(org.springframework.test.util.AopTestUtils.<com.playdata.calen.travel.adapter.out.persistence.JpaTravelClusterDetailAdapter>getUltimateTargetObject(detailStore))
                .page(eq(f.owner()),eq(f.first()),anyInt(),anyInt(),any());
        var request=mvc.perform(get("/api/travel/my-map/photo-clusters/{id}",f.first())
                .param("size","1").param("focusMediaId",Long.toString(f.first()))
                .with(user(AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()))))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(f.first()))
                .andExpect(jsonPath("$.representativeMediaId").value(f.second()))
                .andExpect(jsonPath("$.representativePhoto.id").value(f.second()))
                .andExpect(jsonPath("$.photos[0].id").value(f.first()))
                .andExpect(jsonPath("$.photos[0].contentUrl").value("/api/travel/media/"+f.first()+"/content"))
                .andExpect(jsonPath("$.totalPhotoCount").value(2)).andExpect(jsonPath("$.hasNext").value(true));
        var second=details.page(f.owner(),f.first(),1,1,f.first());
        assertThat(second.photos()).extracting(p->p.id()).containsExactly(f.first()); // focus ignored after page 0
        assertThat(second.hasNext()).isFalse();
        verify(coordinator,times(2)).ensureCurrent(f.owner());
    }

    @Test void readinessWaitSuspendsAnExistingTransactionAndReadTransactionResumesCorrectly() {
        var f=fixture(); projection.apply(f.owner(),projection.compute(f.owner()));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(coordinator).ensureCurrent(f.owner());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(details.page(f.owner(),f.first(),null,null,null).photos()).hasSize(2);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
    }

    @Test void ownerFiltersExcludeForeignMemberAndForeignRepresentativePayloads() throws Exception {
        var f=fixture(); var other=fixture(); projection.apply(f.owner(),projection.compute(f.owner()));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var member=new TravelPhotoClusterMember(); member.setOwnerId(f.owner()); member.setClusterId(f.first());
            member.setMediaId(other.first()); member.setSortOrder(0); members.save(member);
            clusters.findByIdAndOwnerId(f.first(),f.owner()).orElseThrow().setRepresentativeMediaId(other.first());
        });
        var page=details.page(f.owner(),f.first(),0,36,other.first());
        assertThat(page.totalPhotoCount()).isEqualTo(2); assertThat(page.photos()).extracting(p->p.id()).containsExactly(f.second(),f.first());
        assertThat(page.cluster().representativeMediaId()).isEqualTo(other.first());
        assertThat(page.cluster().representativePhoto()).isNull();
        var full=new TransactionTemplate(transactions).execute(tx->detailStore.detail(f.owner(),f.first()).orElseThrow());
        assertThat(full.photos()).extracting(p->p.id()).containsExactlyInAnyOrder(f.first(),f.second());
        clearInvocations(redis,coordinator);
        mvc.perform(put("/api/travel/my-map/photo-clusters/{id}/representative",f.first())
                .with(user(AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":"+other.first()+"}"))
                .andExpect(status().isBadRequest());
        verify(coordinator).lockManualRefresh(f.owner()); verify(coordinator,never()).dispatch(any());
        verifyNoInteractions(redis);
        assertThat(media.findById(other.first()).orElseThrow().getRepresentativeOverride()).isFalse();
    }

    @Test void missingOwnerClusterAndInvalidRequestDoNotExposeOrMutateAnotherOwnersPhotos() throws Exception {
        var f=fixture(); var other=fixture(); projection.apply(f.owner(),projection.compute(f.owner()));
        clearInvocations(redis,coordinator);
        assertThatThrownBy(()->details.page(other.owner(),f.first(),0,12,null))
                .isInstanceOf(com.playdata.calen.common.exception.NotFoundException.class);
        mvc.perform(put("/api/travel/my-map/photo-clusters/{id}/representative",f.first())
                .with(user(AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        verify(coordinator).ensureCurrent(other.owner()); verifyNoMoreInteractions(coordinator); verifyNoInteractions(redis);
    }

    @Test void manualChangeRetainsReadCommittedFlagsCacheTimingAndDispatchAfterCommitOnly() {
        var f=fixture(); projection.apply(f.owner(),projection.compute(f.owner())); clearInvocations(redis,coordinator);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            verify(coordinator,never()).dispatch(any());
            return null;
        }).when(coordinator).lockManualRefresh(f.owner());
        doAnswer(call -> {
            verifySummaryEviction(f.owner(),1); verify(coordinator,never()).dispatch(any());
            assertThat(clusters.findByIdAndOwnerId(f.first(),f.owner()).orElseThrow().getRepresentativeMediaId()).isEqualTo(f.first());
            return null;
        }).when(coordinator).markDirtyInCurrentTransaction(f.owner());
        var result=details.changeRepresentative(f.owner(),f.first(),f.first());
        assertThat(result.photos()).extracting(p->p.id()).containsExactly(f.first(),f.second());
        assertThat(media.findById(f.first()).orElseThrow().getRepresentativeOverride()).isTrue();
        assertThat(media.findById(f.second()).orElseThrow().getRepresentativeOverride()).isFalse();
        verifySummaryEviction(f.owner(),2); verify(coordinator).dispatch(f.owner());
        verify(coordinator).markDirtyInCurrentTransaction(f.owner());
    }

    @Test void rollbackRestoresFlagsAndProjectionAndSuppressesCommitEvictionAndDispatch() {
        var f=fixture(); projection.apply(f.owner(),projection.compute(f.owner())); clearInvocations(redis,coordinator);
        var tx=new TransactionTemplate(transactions); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> {
            details.changeRepresentative(f.owner(),f.first(),f.first());
            assertThat(clusters.findByIdAndOwnerId(f.first(),f.owner()).orElseThrow().getRepresentativeMediaId()).isEqualTo(f.first());
            verifySummaryEviction(f.owner(),1); verify(coordinator,never()).dispatch(any());
            status.setRollbackOnly();
        });
        assertThat(media.findById(f.first()).orElseThrow().getRepresentativeOverride()).isFalse();
        assertThat(media.findById(f.second()).orElseThrow().getRepresentativeOverride()).isTrue();
        assertThat(clusters.findByIdAndOwnerId(f.first(),f.owner()).orElseThrow().getRepresentativeMediaId()).isEqualTo(f.second());
        verifySummaryEviction(f.owner(),1); verify(coordinator,never()).dispatch(any());
    }

    @Test void projectionFailureRollsBackTheAlreadyChangedFlagsAndNeverSchedulesARefresh() {
        var f=fixture(); projection.apply(f.owner(),projection.compute(f.owner())); clearInvocations(redis,coordinator);
        doThrow(new IllegalStateException("projection unavailable")).when(policy).cluster(anyList());
        assertThatThrownBy(()->details.changeRepresentative(f.owner(),f.first(),f.first())).hasMessage("projection unavailable");
        assertThat(media.findById(f.first()).orElseThrow().getRepresentativeOverride()).isFalse();
        assertThat(media.findById(f.second()).orElseThrow().getRepresentativeOverride()).isTrue();
        assertThat(clusters.findByIdAndOwnerId(f.first(),f.owner()).orElseThrow().getRepresentativeMediaId()).isEqualTo(f.second());
        verifySummaryEviction(f.owner(),1); verify(coordinator,never()).dispatch(any()); verify(coordinator,never()).markDirtyInCurrentTransaction(any());
    }

    @Test void detailPersistenceOperationsStillRequireAnEnclosingTransaction() {
        assertThatThrownBy(()->detailStore.page(7L,8L,0,12,null)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->detailStore.detail(7L,8L)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(()->detailStore.changeRepresentative(List.of(8L),8L)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void legacyEntryPointsRemainCompatibleDelegatesForExistingCallers() {
        var f=fixture(); projection.apply(f.owner(),projection.compute(f.owner()));
        assertThat(legacy.getMyMapPhotoClusterDetail(f.owner(),f.first(),0,12,f.first()))
                .isEqualTo(com.playdata.calen.travel.adapter.support.TravelClusterDetailMapper.response(details.page(f.owner(),f.first(),0,12,f.first())));
        var response=legacy.updateMyMapPhotoClusterRepresentative(f.owner(),f.first(),f.first());
        assertThat(response.representativeMediaId()).isEqualTo(f.first());
        assertThat(response.photos()).extracting(p->p.id()).containsExactly(f.first(),f.second());
        assertThat(response.photos().get(0).contentUrl()).isEqualTo("/api/travel/media/"+f.first()+"/content");
    }

    @Test void ownedDetailHttpDefaultsAndAuthenticationRemainUnchanged() throws Exception {
        var f=fixture(); projection.apply(f.owner(),projection.compute(f.owner()));
        var result=mvc.perform(get("/api/travel/my-map/photo-clusters/{id}",f.first())
                .with(user(AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()))))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(12)).andExpect(jsonPath("$.totalPhotoCount").value(2));
        mvc.perform(get("/api/travel/my-map/photo-clusters/{id}",f.first())).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/travel/my-map/photo-clusters/{id}/representative",f.first())
                .with(user(AppUserPrincipal.from(users.findById(f.owner()).orElseThrow())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":"+f.first()+"}")).andExpect(status().isForbidden());
    }

    private Fixture fixture() {
        long owner = new TransactionTemplate(transactions).execute(tx -> {
            var u = new AppUser(); u.setLoginId("cluster-"+UUID.randomUUID()); u.setDisplayName("Cluster test"); u.setPasswordHash("unused-test-hash");
            return users.save(u).getId();
        });
        long plan = new TransactionTemplate(transactions).execute(tx -> {
            var p = new TravelPlan(); p.setOwner(users.getReferenceById(owner)); p.setName("Cluster trip"); p.setColorHex("#abcdef");
            p.setStartDate(LocalDate.of(2026,10,15)); p.setEndDate(LocalDate.of(2026,10,16)); return plans.save(p).getId();
        });
        long first = photo(record(plan, TravelRecordType.MEMORY, "37.5", "127"), TravelMediaType.PHOTO, null, null, false);
        long second = photo(record(plan, TravelRecordType.MEMORY, "37.50001", "127"), TravelMediaType.PHOTO, null, null, true);
        return new Fixture(owner, plan, first, second);
    }
    private long record(long plan, TravelRecordType type, String lat, String lon) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var r = new TravelExpenseRecord(); r.setPlan(plans.getReferenceById(plan)); r.setRecordType(type); r.setExpenseDate(LocalDate.of(2026,10,15));
            r.setExpenseTime(LocalTime.NOON); r.setCategory("Spot"); r.setTitle("Place"); r.setAmount(BigDecimal.ZERO);
            r.setLatitude(lat == null ? null : new BigDecimal(lat)); r.setLongitude(lon == null ? null : new BigDecimal(lon)); return records.save(r).getId();
        });
    }
    private long photo(long record, TravelMediaType type, String lat, String lon, boolean override) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var r = records.findById(record).orElseThrow(); var asset = new TravelMediaAsset(); asset.setRecord(r); asset.setPlan(r.getPlan());
            asset.setUploadedBy(r.getPlan().getOwner()); asset.setMediaType(type); asset.setOriginalFileName("photo.jpg"); asset.setStoredFileName("photo.jpg");
            asset.setStoragePath("test-only/cluster/"+UUID.randomUUID()+".jpg"); asset.setContentType("image/jpeg"); asset.setFileSize(1L);
            asset.setGpsLatitude(lat == null ? null : new BigDecimal(lat)); asset.setGpsLongitude(lon == null ? null : new BigDecimal(lon));
            asset.setRepresentativeOverride(override); return media.save(asset).getId();
        });
    }
    private List<Long> memberIds(long owner) { return members.findAllByOwnerIdOrderByClusterIdAscSortOrderAsc(owner).stream().map(TravelPhotoClusterMember::getId).toList(); }
    private String snapshotKey(long owner) { return "travel:mymap:photo-clusters:"+owner; }
    private void verifySummaryEviction(long owner, int times) {
        verify(redis, times(times)).delete(TravelSummaryCache.plansKey(owner), TravelSummaryCache.portfolioKey(owner), TravelSummaryCache.mapKey(owner), TravelSummaryCache.PUBLIC_TRIPS_KEY);
    }
}
