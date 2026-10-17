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
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.dto.RecordShareDtos;
import com.playdata.calen.sharing.service.RecordSharingService;
import com.playdata.calen.travel.application.port.in.*;
import com.playdata.calen.travel.application.port.out.TravelExhibitReadStore;
import com.playdata.calen.travel.domain.*;
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
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises the real read-only transaction, authorization and HTTP representation boundaries. */
@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:travel-exhibit-reads;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false",
        "app.security.public-media-key=isolated-exhibit-test-key-not-production"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TravelExhibitReadRefactoringIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired TravelMediaAssetRepository assets;
    @Autowired TravelPlanShareRepository shares;
    @Autowired ReadTravelExhibitsUseCase reads;
    @Autowired ManageTravelSharingUseCase sharing;
    @Autowired TravelExhibitReadStore store;
    @Autowired TravelService legacy;
    @Autowired TravelPublicMediaTokenService tokens;
    @Autowired RecordSharingService recordSharing;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean RedisCacheService redis;
    @MockitoBean TravelMediaStorageService storage;
    @MockitoBean TravelDriveLinkService drive;
    @MockitoBean TravelClusterRefreshCoordinator clusters;
    record Fixture(long owner, String login, long plan, long record, long media) {}

    @Test void receivedDetailUsesDetachedValuesAndKeepsScopeSpecificMediaUrlsAndTheOwnedJson() throws Exception {
        var owner = fixture(TravelPlanStatus.COMPLETED, TravelRecordType.MEMORY, TravelMediaType.PHOTO);
        var viewer = fixture(TravelPlanStatus.PLANNED, TravelRecordType.LEDGER, TravelMediaType.RECEIPT);
        long share = sharing.shareCompletedPlan(owner.owner(), owner.plan(), viewer.login()).id();
        var received = mvc.perform(get("/api/travel/shared-exhibits/{id}", share).with(user(principal(viewer))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(share))
                .andExpect(jsonPath("$.travelPlan.mediaItems[0].contentUrl").value("/api/travel/shared-exhibits/" + share + "/media/" + owner.media() + "/content"))
                .andExpect(jsonPath("$.travelPlan.memoryRecordCount").value(1)).andExpect(jsonPath("$.sharedByLoginId").value(owner.login()))
                .andReturn();
        var own = mvc.perform(get("/api/travel/plans/{id}", owner.plan()).with(user(principal(owner)))).andExpect(status().isOk()).andReturn();
        var actual = json.readTree(received.getResponse().getContentAsByteArray()).get("travelPlan");
        var expected = json.readTree(own.getResponse().getContentAsByteArray());
        ((com.fasterxml.jackson.databind.node.ObjectNode) actual.get("mediaItems").get(0)).put("contentUrl", "/api/travel/media/" + owner.media() + "/content");
        assertThat(actual).isEqualTo(expected);
        var detached = reads.receivedDetail(viewer.owner(), share);
        mutate(owner, false, false, TravelPlanStatus.COMPLETED);
        assertThat(json.writeValueAsString(com.playdata.calen.travel.adapter.support.TravelExhibitMapper.received(detached))).contains("Synthetic place");
    }

    @Test void recipientMediaIncludesReceiptsButRejectsForeignPlansAndStopsAfterCancel() throws Exception {
        var owner = fixture(TravelPlanStatus.COMPLETED, TravelRecordType.LEDGER, TravelMediaType.RECEIPT);
        var viewer = fixture(TravelPlanStatus.COMPLETED, TravelRecordType.MEMORY, TravelMediaType.PHOTO);
        long share = sharing.shareCompletedPlan(owner.owner(), owner.plan(), viewer.login()).id();
        when(storage.loadAsResource(path(owner))).thenReturn(new ByteArrayResource(new byte[] {1, 2, 3})); clearInvocations(storage);
        mvc.perform(get("/api/travel/shared-exhibits/{share}/media/{media}/content", share, owner.media()).with(user(principal(viewer))))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF)).andExpect(content().bytes(new byte[] {1, 2, 3}));
        clearInvocations(storage);
        mvc.perform(get("/api/travel/shared-exhibits/{share}/media/{media}/content", share, viewer.media()).with(user(principal(viewer))))
                .andExpect(status().isNotFound()); verifyNoInteractions(storage);
        sharing.cancelShare(owner.owner(), owner.plan(), share);
        mvc.perform(get("/api/travel/shared-exhibits/{share}/media/{media}/content", share, owner.media()).with(user(principal(viewer))))
                .andExpect(status().isNotFound()); verifyNoInteractions(storage);
    }

    @Test void tokenIsPairBoundAndVisibilityIsRecheckedAfterEachSuccessfulDownload() throws Exception {
        var owner = fixture(TravelPlanStatus.COMPLETED, TravelRecordType.MEMORY, TravelMediaType.PHOTO);
        mutate(owner, true, false, TravelPlanStatus.COMPLETED);
        when(storage.loadAsResource(path(owner))).thenReturn(new ByteArrayResource(new byte[] {4}));
        var token = tokens.issueToken(owner.media());
        mvc.perform(get("/api/travel/public/media/{id}/content", owner.media()).param("token", token))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[] {4}));
        clearInvocations(storage);
        mvc.perform(get("/api/travel/public/media/{id}/content", owner.media()).param("token", "invalid-token"))
                .andExpect(status().isNotFound());
        mutate(owner, false, false, TravelPlanStatus.COMPLETED);
        mvc.perform(get("/api/travel/public/media/{id}/content", owner.media()).param("token", token)).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
        mutate(owner, false, true, TravelPlanStatus.PLANNED);
        mvc.perform(get("/api/travel/public/media/{id}/content", owner.media()).param("token", token)).andExpect(status().isOk());
    }

    @Test void validTokensNeverExposeLedgerPhotosOrReceiptMediaEvenIfMarkedPublic() throws Exception {
        for (var recordType : TravelRecordType.values()) for (var mediaType : TravelMediaType.values()) {
            if (recordType == TravelRecordType.MEMORY && mediaType == TravelMediaType.PHOTO) continue;
            var owner = fixture(TravelPlanStatus.COMPLETED, recordType, mediaType);
            mutate(owner, true, true, TravelPlanStatus.COMPLETED);
            mvc.perform(get("/api/travel/public/media/{id}/content", owner.media()).param("token", tokens.issueToken(owner.media())))
                    .andExpect(status().isNotFound());
        }
        verifyNoInteractions(storage);
    }

    @Test void listAndDetailDropPlansThatAreNoLongerCompletedAndNeverWriteCachesOrRows() throws Exception {
        var owner = fixture(TravelPlanStatus.COMPLETED, TravelRecordType.MEMORY, TravelMediaType.PHOTO);
        var viewer = fixture(TravelPlanStatus.COMPLETED, TravelRecordType.LEDGER, TravelMediaType.RECEIPT);
        long share = sharing.shareCompletedPlan(owner.owner(), owner.plan(), viewer.login()).id(); clearInvocations(redis, storage, drive, clusters);
        mvc.perform(get("/api/travel/shared-exhibits").param("page", "-1").param("size", "999").with(user(principal(viewer))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].id").value(share)).andExpect(jsonPath("$.items[0].mediaItemCount").value(1));
        mutate(owner, false, false, TravelPlanStatus.PLANNED);
        mvc.perform(get("/api/travel/shared-exhibits/{id}", share).with(user(principal(viewer)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/travel/shared-exhibits").with(user(principal(viewer)))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        assertThat(shares.findById(share)).isPresent(); verifyNoInteractions(redis, storage, drive, clusters);
    }

    @Test void trustedRecordBridgeRetainsOwnerScopeAndDoesNotRequireCompletedOrPublicPlans() {
        var owner = fixture(TravelPlanStatus.PLANNED, TravelRecordType.LEDGER, TravelMediaType.RECEIPT);
        var other = fixture(TravelPlanStatus.PLANNED, TravelRecordType.MEMORY, TravelMediaType.PHOTO);
        var detail = legacy.getRecordSharedPlanDetail(owner.owner(), owner.plan(), 99L);
        assertThat(detail.sharedAt()).isNull(); assertThat(detail.sharedByLoginId()).isEqualTo(owner.login());
        assertThat(detail.travelPlan().mediaItems().get(0).contentUrl()).isEqualTo("/api/travel/record-shares/99/media/" + owner.media() + "/content");
        assertThat(legacy.getRecordSharedMediaDownload(owner.owner(), owner.plan(), owner.media()).contentType()).isEqualTo("application/pdf");
        assertThatThrownBy(() -> reads.recordShareMedia(other.owner(), owner.plan(), owner.media())).hasMessage("Travel plan not found.");
        assertThatThrownBy(() -> reads.recordShareMedia(owner.owner(), owner.plan(), other.media())).hasMessage("Shared travel media not found.");
        verifyNoInteractions(redis, storage, drive, clusters);
    }

    @Test void recordShareHttpRequiresAcceptanceKeepsPrivateThumbnailsAndRevokesAfterCancel() throws Exception {
        var owner = fixture(TravelPlanStatus.PLANNED, TravelRecordType.MEMORY, TravelMediaType.PHOTO);
        var viewer = fixture(TravelPlanStatus.PLANNED, TravelRecordType.MEMORY, TravelMediaType.PHOTO);
        var group = sharing.saveGroup(owner.owner(), "Scope " + UUID.randomUUID(), List.of(viewer.login()));
        long share = recordSharing.create(owner.owner(), new RecordShareDtos.Create(group.id(), RecordShareKind.TRAVEL, owner.plan(), List.of(viewer.owner()))).get(0).id();
        var thumbnail = new ByteArrayResource(new byte[] {9, 8, 7});
        when(storage.loadThumbnail(path(owner), "image/jpeg", 320)).thenReturn(new TravelMediaStorageService.PreparedThumbnail(thumbnail, "image/jpeg"));
        mvc.perform(get("/api/travel/record-shares/{share}/media/{media}/content", share, owner.media())
                .param("thumbnail", "true").param("w", "320").with(user(principal(viewer)))).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
        mvc.perform(post("/api/record-shares/{id}/accept-travel", share).with(user(principal(viewer))).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/record-shares/{id}/travel", share).with(user(principal(viewer)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.travelPlan.status").value("PLANNED"))
                .andExpect(jsonPath("$.travelPlan.mediaItems[0].contentUrl").value("/api/travel/record-shares/" + share + "/media/" + owner.media() + "/content"));
        mvc.perform(get("/api/travel/record-shares/{share}/media/{media}/content", share, owner.media())
                .param("thumbnail", "true").param("w", "320").with(user(principal(viewer)))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store")).andExpect(content().bytes(new byte[] {9, 8, 7}));
        clearInvocations(storage);
        mvc.perform(post("/api/record-shares/{id}/cancel", share).with(user(principal(owner))).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/travel/record-shares/{share}/media/{media}/content", share, owner.media()).with(user(principal(viewer)))).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    @Test void removingCurrentGroupMembershipBlocksAcceptedRecordMediaEvenWhenOwnerMetadataIsCached() throws Exception {
        var owner = fixture(TravelPlanStatus.PLANNED, TravelRecordType.LEDGER, TravelMediaType.RECEIPT);
        var viewer = fixture(TravelPlanStatus.PLANNED, TravelRecordType.MEMORY, TravelMediaType.PHOTO);
        var other = fixture(TravelPlanStatus.PLANNED, TravelRecordType.MEMORY, TravelMediaType.PHOTO);
        String name = "Scope " + UUID.randomUUID();
        var group = sharing.saveGroup(owner.owner(), name, List.of(viewer.login(), other.login()));
        long share = recordSharing.create(owner.owner(), new RecordShareDtos.Create(group.id(), RecordShareKind.TRAVEL, owner.plan(), List.of(viewer.owner()))).get(0).id();
        recordSharing.acceptTravel(viewer.owner(), share);
        legacy.getMediaDownload(owner.owner(), owner.media());
        sharing.saveGroup(owner.owner(), name, List.of(other.login()));
        clearInvocations(storage);
        mvc.perform(get("/api/record-shares/{id}/travel", share).with(user(principal(viewer)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/travel/record-shares/{share}/media/{media}/content", share, owner.media()).with(user(principal(viewer)))).andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }

    @Test void authenticationInactiveViewerAndMandatoryAdapterRulesRemainEnforced() throws Exception {
        var owner = fixture(TravelPlanStatus.COMPLETED, TravelRecordType.MEMORY, TravelMediaType.PHOTO);
        var viewer = fixture(TravelPlanStatus.COMPLETED, TravelRecordType.MEMORY, TravelMediaType.PHOTO);
        long share = sharing.shareCompletedPlan(owner.owner(), owner.plan(), viewer.login()).id();
        mvc.perform(get("/api/travel/shared-exhibits")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/travel/shared-exhibits/{id}", share).with(user(principal(owner)))).andExpect(status().isNotFound());
        assertThatThrownBy(() -> store.receivedShare(viewer.owner(), share)).isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> users.findById(viewer.owner()).orElseThrow().setActive(false));
        assertThatThrownBy(() -> reads.receivedDetail(viewer.owner(), share)).hasMessage("사용자 계정을 찾을 수 없습니다.");
        verifyNoInteractions(storage);
    }

    Fixture fixture(TravelPlanStatus status, TravelRecordType recordType, TravelMediaType mediaType) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var account = new AppUser(); account.setLoginId("exhibit-" + UUID.randomUUID()); account.setDisplayName("Synthetic owner"); account.setPasswordHash("unused-test-hash"); users.save(account);
            var plan = new TravelPlan(); plan.setOwner(account); plan.setName("Synthetic trip"); plan.setColorHex("#abcdef"); plan.setStatus(status);
            plan.setStartDate(LocalDate.of(2026, 10, 16)); plan.setEndDate(LocalDate.of(2026, 10, 17)); plans.save(plan);
            var record = new TravelExpenseRecord(); record.setPlan(plan); record.setRecordType(recordType); record.setTitle("Synthetic place"); record.setCategory("Place");
            record.setExpenseDate(plan.getStartDate()); record.setExpenseTime(LocalTime.of(12, 34)); record.setAmount(BigDecimal.ZERO); records.save(record);
            var asset = new TravelMediaAsset(); asset.setPlan(plan); asset.setRecord(record); asset.setUploadedBy(account); asset.setMediaType(mediaType);
            asset.setOriginalFileName(mediaType == TravelMediaType.RECEIPT ? "receipt.pdf" : "photo.jpg"); asset.setStoredFileName(asset.getOriginalFileName());
            asset.setStoragePath("test-only/exhibit/" + UUID.randomUUID()); asset.setContentType(mediaType == TravelMediaType.RECEIPT ? "application/pdf" : "image/jpeg"); asset.setFileSize(3L); assets.save(asset);
            return new Fixture(account.getId(), account.getLoginId(), plan.getId(), record.getId(), asset.getId());
        });
    }
    void mutate(Fixture f, boolean publicPlan, boolean community, TravelPlanStatus status) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { var plan = plans.findById(f.plan()).orElseThrow(); plan.setPublicShared(publicPlan); plan.setStatus(status);
            records.findById(f.record()).orElseThrow().setSharedWithCommunity(community); });
    }
    String path(Fixture f) { return assets.findById(f.media()).orElseThrow().getStoragePath(); }
    AppUserPrincipal principal(Fixture f) { return AppUserPrincipal.from(users.findById(f.owner()).orElseThrow()); }
}
