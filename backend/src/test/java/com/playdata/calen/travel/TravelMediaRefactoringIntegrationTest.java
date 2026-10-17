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
import com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache;
import com.playdata.calen.travel.application.port.in.ManageOwnedTravelMediaUseCase;
import com.playdata.calen.travel.application.port.out.OwnedTravelMediaCachePort;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.Completed;
import com.playdata.calen.travel.dto.*;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:travel-media-refactoring;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelMediaRefactoringIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired TravelMediaAssetRepository assets;
    @Autowired ManageOwnedTravelMediaUseCase media;
    @Autowired OwnedTravelMediaCachePort cache;
    @Autowired TravelService legacy;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean TravelDriveLinkService drive;
    @MockitoBean TravelClusterRefreshCoordinator coordinator;
    record Fixture(long owner, long plan, long record) {}

    @Test void presignEndpointsKeepScopeNestedThumbnailsAndResponseNames() throws Exception {
        for (var type : TravelRecordType.values()) {
            var f = fixture(type);
            when(storage.supportsPresignedUpload()).thenReturn(true);
            when(storage.preparePresignedUploads(eq(f.owner()), eq(f.plan()), eq(f.record()), any())).thenReturn(List.of(
                    new TravelMediaStorageService.PresignedUpload("PUT", "https://storage.invalid/file", "scoped/key", "stored.jpg", "photo.jpg", "image/jpeg", 12,
                            List.of(new TravelMediaStorageService.PresignedThumbnailUpload("480", "PUT", "https://storage.invalid/thumb", "scoped/thumb", "image/png", 4)))));
            mvc.perform(post(base(type), f.record()).with(user(principal(f))).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(new TravelMediaUploadPrepareRequest(
                            TravelMediaType.PHOTO, "caption", List.of(new TravelMediaUploadFileRequest("photo.jpg", "image/jpeg", 12,
                                    List.of(new TravelPreparedThumbnailUploadRequest("480", "image/png", 4))))))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.uploadMode").value("PRESIGNED"))
                    .andExpect(jsonPath("$.uploads[0].objectKey").value("scoped/key"))
                    .andExpect(jsonPath("$.uploads[0].thumbnails[0].variant").value("480"))
                    .andExpect(jsonPath("$.uploads[0].thumbnails[0].fileSize").value(4));
            verify(storage).preparePresignedUploads(f.owner(), f.plan(), f.record(), List.of(
                    new TravelMediaStorageService.UploadCandidate("photo.jpg", "image/jpeg", 12,
                            List.of(new TravelMediaStorageService.PreparedThumbnailUploadCandidate("480", "image/png", 4)))));
        }
        verifyNoInteractions(drive, redis, coordinator);
    }

    @Test void memoryCompletionKeepsRichJsonForcedPhotoTrimmedCaptionAndRoundedGps() throws Exception {
        var f = fixture(TravelRecordType.MEMORY);
        stubStorage(f, "image/jpeg");
        var response = mvc.perform(post("/api/travel/memories/{id}/media/complete", f.record()).with(user(principal(f))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(TravelMediaType.RECEIPT))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].recordId").value(f.record()))
                .andExpect(jsonPath("$[0].planId").value(f.plan())).andExpect(jsonPath("$[0].planColorHex").value("#ABCDEF"))
                .andExpect(jsonPath("$[0].mediaType").value("PHOTO")).andExpect(jsonPath("$[0].caption").value("caption"))
                .andExpect(jsonPath("$[0].uploadedBy").value("Synthetic media owner"))
                .andExpect(jsonPath("$[0].recordType").value("MEMORY")).andExpect(jsonPath("$[0].title").value("Synthetic place"))
                .andExpect(jsonPath("$[0].expenseDate").value("2026-10-16")).andExpect(jsonPath("$[0].expenseTime").value("12:34:00"))
                .andExpect(jsonPath("$[0].fileSize").value(10)).andExpect(jsonPath("$[0].representativeOverride").value(false))
                .andReturn();
        long id = json.readTree(response.getResponse().getContentAsByteArray()).get(0).get("id").asLong();
        assertThat(json.readTree(response.getResponse().getContentAsByteArray()).get(0).get("contentUrl").asText())
                .isEqualTo("/api/travel/media/" + id + "/content");
        var saved = assets.findById(id).orElseThrow();
        assertThat(saved.getCaption()).isEqualTo("caption");
        assertThat(saved.getGpsLatitude()).isEqualByComparingTo("37.1234568");
        assertThat(saved.getGpsLongitude()).isEqualByComparingTo("127.1234568");
        assertThat(saved.getGpsExtractedAt()).isNotNull();
        verify(drive).linkMediaAsset(argThat(asset -> asset.getId().equals(id)));
        verify(coordinator).markDirtyInCurrentTransaction(f.owner());
        verify(coordinator).dispatch(f.owner());
        verifyEviction(f, 2);
    }

    @Test void ledgerReceiptCompletionAndLegacyFacadeRetainPdfAndDoNotRefreshPhotoClusters() throws Exception {
        var f = fixture(TravelRecordType.LEDGER);
        stubStorage(f, "application/pdf");
        mvc.perform(post("/api/travel/records/{id}/media/complete", f.record()).with(user(principal(f))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(TravelMediaType.RECEIPT))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].mediaType").value("RECEIPT"))
                .andExpect(jsonPath("$[0].contentType").value("application/pdf"));
        var response = legacy.completeRecordMediaUpload(f.owner(), f.record(), request(TravelMediaType.RECEIPT));
        assertThat(response).hasSize(1);
        assertThat(legacy.getMediaDownload(f.owner(), response.get(0).id()).contentType()).isEqualTo("application/pdf");
        verifyNoInteractions(coordinator);
    }

    @Test void foreignOwnerAndWrongRecordKindFailBeforeObjectVerificationOrMutation() throws Exception {
        var memory = fixture(TravelRecordType.MEMORY);
        var other = fixture(TravelRecordType.LEDGER);
        when(storage.supportsPresignedUpload()).thenReturn(true);
        for (String path : List.of("/api/travel/memories/{id}/media/presign", "/api/travel/records/{id}/media/presign")) {
            mvc.perform(post(path, memory.record()).with(user(principal(other))).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(new TravelMediaUploadPrepareRequest(TravelMediaType.PHOTO, null,
                            List.of(new TravelMediaUploadFileRequest("p.jpg", "image/jpeg", 1, null)))))).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/travel/records/{id}/media/complete", memory.record()).with(user(principal(memory))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request(TravelMediaType.PHOTO)))).andExpect(status().isNotFound());
        assertThatThrownBy(() -> media.complete(other.owner(), memory.record(), TravelRecordType.MEMORY, TravelMediaType.PHOTO, null, completed()))
                .hasMessage("Travel memory not found.");
        verify(storage, never()).preparePresignedUploads(any(), any(), any(), any());
        verify(storage, never()).completePresignedUpload(any(), any(), any(), any());
        verifyNoInteractions(drive, redis, coordinator);
    }

    @Test void legacyNullRecordKindStillAcceptsLedgerReceiptsButIsNotAMemory() {
        var f = fixture(null);
        stubStorage(f, "application/pdf");
        var response = media.complete(f.owner(), f.record(), TravelRecordType.LEDGER, TravelMediaType.RECEIPT, " ", completed());
        assertThat(response).hasSize(1);
        assertThat(response.get(0).recordType()).isNull();
        assertThat(response.get(0).caption()).isNull();
        assertThatThrownBy(() -> media.prepare(f.owner(), f.record(), TravelRecordType.MEMORY, List.of()))
                .hasMessage("Travel memory not found.");
        verifyNoInteractions(coordinator);
    }

    @Test void memoryPdfIsRejectedAfterStorageVerificationWithoutSavingOrLinking() {
        var f = fixture(TravelRecordType.MEMORY);
        stubStorage(f, "application/pdf");
        long before = assets.count();
        assertThatThrownBy(() -> media.complete(f.owner(), f.record(), TravelRecordType.MEMORY, TravelMediaType.RECEIPT, null, completed()))
                .hasMessage("Photos must use JPG, PNG, WEBP, GIF, or BMP files.");
        assertThat(assets.count()).isEqualTo(before);
        verify(storage).completePresignedUpload(eq(f.owner()), eq(f.plan()), eq(f.record()), any());
        verifyNoInteractions(drive, redis, coordinator);
    }

    @Test void secondObjectFailureRollsBackFirstInsertAndNeverLinksOrDeletesUnconfirmedFiles() {
        var f = fixture(TravelRecordType.MEMORY);
        stubStorage(f, "image/jpeg");
        when(storage.completePresignedUpload(eq(f.owner()), eq(f.plan()), eq(f.record()), any()))
                .thenReturn(stored("image/jpeg")).thenThrow(new IllegalStateException("synthetic second stat failure"));
        long before = assets.count();
        assertThatThrownBy(() -> media.complete(f.owner(), f.record(), TravelRecordType.MEMORY, TravelMediaType.PHOTO, "caption",
                List.of(completed().get(0), completed().get(0)))).hasMessage("synthetic second stat failure");
        assertThat(assets.count()).isEqualTo(before);
        verify(storage, never()).deleteImageWithThumbnailsQuietly(any(), any());
        verifyNoInteractions(drive, redis, coordinator);
    }

    @Test void responseSnapshotFailureRollsBackAfterLinkWithoutInventingPhysicalStorageCompensation() {
        var f = fixture(TravelRecordType.MEMORY);
        stubStorage(f, "image/jpeg");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> plans.findById(f.plan()).orElseThrow().setColorHex("broken"));
        long before = assets.count();
        assertThatThrownBy(() -> media.complete(f.owner(), f.record(), TravelRecordType.MEMORY, TravelMediaType.PHOTO, null, completed()))
                .hasMessage("Travel color must use #RRGGBB format.");
        assertThat(assets.count()).isEqualTo(before);
        verify(drive).linkMediaAsset(any());
        verify(storage, never()).deleteImageWithThumbnailsQuietly(any(), any());
        verifyNoInteractions(redis, coordinator);
    }

    @Test void batchLinksOnlyAfterAllInsertsAndCommitCallbacksAreNotRunOnOuterRollback() {
        var f = fixture(TravelRecordType.MEMORY);
        stubStorage(f, "image/jpeg");
        long before = assets.count();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(assets.count()).isEqualTo(before + 2);
            verify(storage, times(2)).completePresignedUpload(eq(f.owner()), eq(f.plan()), eq(f.record()), any());
            return null;
        }).when(drive).linkMediaAsset(any());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(media.complete(f.owner(), f.record(), TravelRecordType.MEMORY, TravelMediaType.PHOTO, "caption",
                    List.of(completed().get(0), completed().get(0)))).hasSize(2);
            verifyEviction(f, 1);
            verify(coordinator).markDirtyInCurrentTransaction(f.owner());
            verify(coordinator, never()).dispatch(any());
            tx.setRollbackOnly();
        });
        assertThat(assets.count()).isEqualTo(before);
        verifyEviction(f, 1);
        verify(coordinator, never()).dispatch(any());
    }

    @Test void downloadAndDeleteEndpointsKeepOwnerCacheIsolationAndImmediateEviction() throws Exception {
        var f = fixture(TravelRecordType.MEMORY);
        var other = fixture(TravelRecordType.MEMORY);
        stubStorage(f, "image/jpeg");
        long id = media.complete(f.owner(), f.record(), TravelRecordType.MEMORY, TravelMediaType.PHOTO, null, completed()).get(0).id();
        clearInvocations(storage, drive, redis, coordinator);
        when(storage.loadAsResource("test-only/photo.jpg")).thenReturn(new ByteArrayResource(new byte[] {1, 2, 3}));
        mvc.perform(get("/api/travel/media/{id}/content", id).with(user(principal(f)))).andExpect(status().isOk())
                .andExpect(content().bytes(new byte[] {1, 2, 3})).andExpect(header().string("X-Content-Type-Options", "nosniff"));
        assertThat(cache.get(f.owner(), id)).isNotNull();
        assertThat(cache.get(other.owner(), id)).isNull();
        clearInvocations(storage);
        mvc.perform(get("/api/travel/media/{id}/content", id).with(user(principal(other)))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/travel/media/{id}", id).with(user(principal(other))).with(csrf())).andExpect(status().isNotFound());
        verifyNoInteractions(storage, drive, redis, coordinator);
        mvc.perform(delete("/api/travel/media/{id}", id).with(user(principal(f))).with(csrf())).andExpect(status().isOk());
        assertThat(assets.findById(id)).isEmpty();
        assertThat(cache.get(f.owner(), id)).isNull();
        var ordered = inOrder(drive, storage);
        ordered.verify(drive).removeMediaLink(argThat(asset -> asset.getId().equals(id)));
        ordered.verify(storage).deleteImageWithThumbnailsQuietly("test-only/photo.jpg", "image/jpeg");
        verifyEviction(f, 2);
        verify(coordinator).dispatch(f.owner());
        mvc.perform(get("/api/travel/media/{id}/content", id).with(user(principal(f)))).andExpect(status().isNotFound());
    }

    @Test void storageUnavailableAndInactiveOwnerKeepTheirExistingFailureOrder() {
        var f = fixture(TravelRecordType.MEMORY);
        assertThatThrownBy(() -> media.prepare(-1L, f.record(), TravelRecordType.MEMORY, List.of()))
                .hasMessage("Travel memory not found.");
        assertThatThrownBy(() -> media.complete(-1L, f.record(), TravelRecordType.MEMORY, TravelMediaType.PHOTO, null, completed()))
                .hasMessage("MinIO presigned upload is not available.");
        when(storage.supportsPresignedUpload()).thenReturn(true);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> users.findById(f.owner()).orElseThrow().setActive(false));
        assertThatThrownBy(() -> media.complete(f.owner(), f.record(), TravelRecordType.MEMORY, TravelMediaType.PHOTO, null, completed()))
                .hasMessage("사용자 계정을 찾을 수 없습니다.");
        verify(storage, never()).completePresignedUpload(any(), any(), any(), any());
        verifyNoInteractions(drive, redis, coordinator);
    }

    @Test void rolledBackDeleteRestoresOnlyDatabaseStateAndDoesNotDispatchCommitCallbacks() {
        var f = fixture(TravelRecordType.MEMORY);
        stubStorage(f, "image/jpeg");
        long id = media.complete(f.owner(), f.record(), TravelRecordType.MEMORY, TravelMediaType.PHOTO, null, completed()).get(0).id();
        media.download(f.owner(), id);
        clearInvocations(storage, drive, redis, coordinator);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            media.delete(f.owner(), id);
            assertThat(assets.findById(id)).isEmpty();
            assertThat(cache.get(f.owner(), id)).isNull();
            verifyEviction(f, 1);
            verify(coordinator).markDirtyInCurrentTransaction(f.owner());
            tx.setRollbackOnly();
        });
        assertThat(assets.findById(id)).isPresent();
        assertThat(cache.get(f.owner(), id)).isNull();
        verify(storage).deleteImageWithThumbnailsQuietly("test-only/photo.jpg", "image/jpeg");
        verifyEviction(f, 1);
        verify(coordinator, never()).dispatch(any());
    }

    @Test void authenticationCsrfAndNestedBeanValidationRemainEnforced() throws Exception {
        var f = fixture(TravelRecordType.MEMORY);
        mvc.perform(post("/api/travel/memories/{id}/media/complete", f.record()).with(user(principal(f))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/travel/memories/{id}/media/presign", f.record()).with(user(principal(f))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"mediaType\":\"PHOTO\",\"files\":[{\"originalFileName\":\"p.jpg\",\"contentType\":\"image/jpeg\",\"fileSize\":0}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/travel/media/99999").with(user(principal(f)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/travel/media/99999/content")).andExpect(status().isUnauthorized());
        verifyNoInteractions(storage, drive, redis, coordinator);
    }

    Fixture fixture(TravelRecordType type) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var user = new AppUser();
            user.setLoginId("media-" + UUID.randomUUID()); user.setDisplayName("Synthetic media owner"); user.setPasswordHash("unused-test-hash"); users.save(user);
            var plan = new TravelPlan();
            plan.setOwner(user); plan.setName("Synthetic trip"); plan.setColorHex("#abcdef");
            plan.setStartDate(LocalDate.of(2026, 10, 16)); plan.setEndDate(LocalDate.of(2026, 10, 17)); plans.save(plan);
            var record = new TravelExpenseRecord();
            record.setPlan(plan); record.setRecordType(type); record.setExpenseDate(plan.getStartDate()); record.setExpenseTime(LocalTime.of(12, 34));
            record.setTitle("Synthetic place"); record.setCategory("place"); record.setAmount(BigDecimal.ZERO);
            record.setLatitude(new BigDecimal("37.1")); record.setLongitude(new BigDecimal("127.1")); records.save(record);
            return new Fixture(user.getId(), plan.getId(), record.getId());
        });
    }
    String base(TravelRecordType type) { return type == TravelRecordType.MEMORY ? "/api/travel/memories/{id}/media/presign" : "/api/travel/records/{id}/media/presign"; }
    AppUserPrincipal principal(Fixture f) { return AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()); }
    void stubStorage(Fixture f, String mime) {
        when(storage.supportsPresignedUpload()).thenReturn(true);
        when(storage.completePresignedUpload(eq(f.owner()), eq(f.plan()), eq(f.record()), any())).thenReturn(stored(mime));
    }
    TravelMediaStorageService.StoredTravelMedia stored(String mime) { return new TravelMediaStorageService.StoredTravelMedia("photo.jpg", "stored.jpg", "test-only/photo.jpg", mime, 10); }
    List<Completed> completed() { return List.of(new Completed("scoped/key", "photo.jpg", "image/jpeg", 12, null, new BigDecimal("37.123456789"), new BigDecimal("127.123456789"))); }
    TravelMediaUploadCompleteRequest request(TravelMediaType type) { return new TravelMediaUploadCompleteRequest(type, " caption ",
            List.of(new TravelMediaUploadCompleteFileRequest("scoped/key", "photo.jpg", "image/jpeg", 12, null, new BigDecimal("37.123456789"), new BigDecimal("127.123456789")))); }
    void verifyEviction(Fixture f, int count) { verify(redis, times(count)).delete(TravelSummaryCache.plansKey(f.owner()), TravelSummaryCache.portfolioKey(f.owner()), TravelSummaryCache.mapKey(f.owner()), TravelSummaryCache.PUBLIC_TRIPS_KEY); }
}
