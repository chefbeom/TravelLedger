package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.in.ManageOwnedTravelMediaUseCase;
import com.playdata.calen.travel.application.port.in.ManageTravelRouteFilesUseCase;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelMediaData;
import com.playdata.calen.travel.domain.model.TravelRouteUploadData;
import com.playdata.calen.travel.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "MINIO_CLOUD_BUCKET=travel-test", "MINIO_PRESIGNED_URL_EXPIRY_SECONDS=600",
        "spring.datasource.url=jdbc:h2:mem:travel-grant-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class TravelUploadGrantIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired TravelRouteSegmentRepository routes;
    @Autowired TravelUploadGrantRepository grants;
    @Autowired ManageOwnedTravelMediaUseCase media;
    @Autowired ManageTravelRouteFilesUseCase routeFiles;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean TravelMediaStorageService storage;
    private final String url = "https://store.example/file?X-Amz-Date=20261103T010203Z&X-Amz-Expires=600&X-Amz-Signature=PRIVATE";
    private record Fixture(long owner, long plan, long record, long route) {}

    @Test void mediaAndRouteIssuanceCommitTrackingBeforeReturningUnchangedUrls() {
        var f = fixture();
        String prefix = "travel-media/" + f.owner() + "/" + f.plan();
        String original = prefix + "/" + f.record() + "/photo.jpg";
        String thumbnail = prefix + "/" + f.record() + "/.thumbs/480/photo.jpg";
        String gpx = prefix + "/routes/" + f.route() + "/route.gpx";
        when(storage.supportsPresignedUpload()).thenReturn(true);
        when(storage.preparePresignedUploads(eq(f.owner()), eq(f.plan()), eq(f.record()), anyList())).thenReturn(List.of(
                new TravelMediaStorageService.PresignedUpload("PUT", url, original, "photo.jpg", "photo.jpg", "image/jpeg", 1,
                        List.of(new TravelMediaStorageService.PresignedThumbnailUpload("preview", "PUT", url, thumbnail, "image/jpeg", 1)))));
        when(storage.preparePresignedRouteGpxUploads(eq(f.owner()), eq(f.plan()), eq(f.route()), anyList())).thenReturn(List.of(
                new TravelMediaStorageService.PresignedRouteGpxUpload("PUT", url, gpx, "route.gpx", "route.gpx", "xml", 1)));

        assertThat(media.prepare(f.owner(), f.record(), TravelRecordType.MEMORY, mediaFiles()).uploads().get(0).uploadUrl()).isEqualTo(url);
        assertThat(routeFiles.prepare(f.owner(), f.route(), List.of(new TravelRouteUploadData.Candidate("route.gpx", "xml", 1)))
                .uploads().get(0).uploadUrl()).isEqualTo(url);
        var saved = grants.findAllByOwnerIdOrderByIdAsc(f.owner());
        assertThat(saved).extracting(TravelUploadGrant::getKind).containsExactly(TravelUploadGrant.Kind.MEDIA,
                TravelUploadGrant.Kind.THUMBNAIL, TravelUploadGrant.Kind.ROUTE_GPX);
        assertThat(saved.get(1).getOriginalKey()).isEqualTo(original);
        assertThat(saved.get(2).getStorageBucket()).isEqualTo("travel-test");
        assertThat(saved.get(2).getExpiresAtUtc()).isEqualTo(LocalDateTime.of(2026, 11, 3, 1, 12, 3));
    }

    @Test void malformedThumbnailRollsBackEarlierOriginalAndReturnsNoUrl() {
        var f = fixture(); String prefix = "travel-media/" + f.owner() + "/" + f.plan() + "/" + f.record();
        when(storage.supportsPresignedUpload()).thenReturn(true);
        when(storage.preparePresignedUploads(eq(f.owner()), eq(f.plan()), eq(f.record()), anyList())).thenReturn(List.of(
                new TravelMediaStorageService.PresignedUpload("PUT", url, prefix + "/photo.jpg", "photo.jpg", "photo.jpg", "image/jpeg", 1,
                        List.of(new TravelMediaStorageService.PresignedThumbnailUpload("preview", "PUT", url, prefix + "/.thumbs/480/other.jpg", "image/jpeg", 1)))));
        assertThatThrownBy(() -> media.prepare(f.owner(), f.record(), TravelRecordType.MEMORY, mediaFiles()))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining("PRIVATE");
        assertThat(grants.findAllByOwnerIdOrderByIdAsc(f.owner())).isEmpty();
    }

    @Test void issuanceWaitingForDeactivationCannotProduceAnUntrackedUrl() throws Exception {
        var f = fixture(); var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.lockForAccountSecurityUpdate(f.owner()).orElseThrow().setActive(false);
                users.flush(); locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var issue = pool.submit(() -> media.prepare(f.owner(), f.record(), TravelRecordType.MEMORY, mediaFiles()));
            assertThatThrownBy(() -> issue.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); deactivate.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> issue.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> routeFiles.prepare(f.owner(), f.route(), List.of(new TravelRouteUploadData.Candidate("r.gpx", "xml", 1))))
                    .isInstanceOf(NotFoundException.class);
            verify(storage, never()).preparePresignedUploads(eq(f.owner()), eq(f.plan()), eq(f.record()), anyList());
            verify(storage, never()).preparePresignedRouteGpxUploads(eq(f.owner()), eq(f.plan()), eq(f.route()), anyList());
            assertThat(grants.findAllByOwnerIdOrderByIdAsc(f.owner())).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private List<TravelMediaData.Candidate> mediaFiles() {
        return List.of(new TravelMediaData.Candidate("photo.jpg", "image/jpeg", 1, List.of()));
    }

    private Fixture fixture() {
        return new TransactionTemplate(transactions).execute(status -> {
            var user = new AppUser(); user.setLoginId("travel-grant-" + UUID.randomUUID()); user.setDisplayName("Owner"); users.save(user);
            var plan = new TravelPlan(); plan.setOwner(user); plan.setName("Trip"); plan.setColorHex("#abcdef");
            plan.setStartDate(LocalDate.of(2026, 10, 16)); plan.setEndDate(LocalDate.of(2026, 10, 17)); plans.save(plan);
            var record = new TravelExpenseRecord(); record.setPlan(plan); record.setRecordType(TravelRecordType.MEMORY);
            record.setExpenseDate(plan.getStartDate()); record.setExpenseTime(LocalTime.NOON); record.setTitle("Place");
            record.setCategory("place"); record.setAmount(BigDecimal.ZERO); records.save(record);
            var route = new TravelRouteSegment(); route.setPlan(plan); route.setRouteDate(plan.getStartDate());
            route.setTitle("Route"); route.setRoutePathJson("[]"); routes.save(route);
            return new Fixture(user.getId(), plan.getId(), record.getId(), route.getId());
        });
    }
}
