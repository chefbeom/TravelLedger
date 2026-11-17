package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.drive.domain.DriveDownloadLink;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.service.DriveDownloadLinkService;
import com.playdata.calen.ledger.application.port.in.ReflectTravelLedgerUseCase;
import com.playdata.calen.recordmap.domain.PlaceCollection;
import com.playdata.calen.recordmap.domain.RecordMapShare;
import com.playdata.calen.recordmap.dto.RecordMapDtos.ShareRequest;
import com.playdata.calen.recordmap.service.RecordMapShareService;
import com.playdata.calen.travel.application.port.in.TravelMapLinksUseCase;
import com.playdata.calen.travel.application.port.in.WriteTravelPlanUseCase;
import com.playdata.calen.travel.application.port.in.WriteTravelRecordUseCase;
import com.playdata.calen.travel.application.port.out.TravelPlanStore;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelMapShareLink;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.service.TravelService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.account-withdrawal.internal-workflow-enabled=true",
        "spring.datasource.url=jdbc:h2:mem:public-suspension-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class PublicShareWithdrawalSuspenderIntegrationTest {
    @Autowired AppUserRepository users;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired PublicShareWithdrawalSuspender suspender;
    @Autowired EntityManager entities;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RecordMapShareService recordMap;
    @Autowired TravelMapLinksUseCase travelLinks;
    @Autowired DriveDownloadLinkService driveLinks;
    @Autowired TravelService travel;
    @Autowired WriteTravelRecordUseCase travelRecords;
    @Autowired WriteTravelPlanUseCase travelPlans;
    @Autowired TravelPlanStore planStore;
    @Autowired ReflectTravelLedgerUseCase travelLedger;

    @Test void aCachedOwnerCannotContinueAPublicDownloadAfterWithdrawal() throws Exception {
        Fixture owner = fixture();
        var requestEntities = entityManagerFactory.createEntityManager();
        var pool = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntities));
        try {
            AppUser stale = requestEntities.find(AppUser.class, owner.owner());
            requestEntities.find(DriveDownloadLink.class, owner.driveLink());
            pool.submit(() -> tx(() -> {
                users.lockForAccountSecurityUpdate(owner.owner()).orElseThrow().setActive(false);
                request(owner.owner());
                suspender.suspend(owner.owner(), LocalDateTime.now());
            })).get(5, TimeUnit.SECONDS);
            assertThat(stale.isActive()).isTrue();
            assertThatThrownBy(() -> driveLinks.downloadByToken(owner.driveToken())).isInstanceOf(BadRequestException.class);
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntities.close();
            pool.shutdownNow();
        }
        assertPublished(owner, false);
    }

    @Test void aCachedPublicLinkCannotBeUsedAfterItWasRevoked() throws Exception {
        Fixture owner = fixture();
        var requestEntities = entityManagerFactory.createEntityManager();
        var pool = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntities));
        try {
            DriveDownloadLink stale = requestEntities.find(DriveDownloadLink.class, owner.driveLink());
            pool.submit(() -> driveLinks.revokeLink(owner.owner(), owner.driveLink())).get(5, TimeUnit.SECONDS);
            assertThat(stale.getRevokedAt()).isNull();
            assertThatThrownBy(() -> driveLinks.downloadByToken(owner.driveToken())).isInstanceOf(BadRequestException.class);
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntities.close();
            pool.shutdownNow();
        }
        assertThat(users.findById(owner.owner()).orElseThrow().isActive()).isTrue();
    }

    @Test void suspensionRequiresAPendingWithdrawalAndPreservesOtherOwnersShares() {
        Fixture owner = fixture(), other = fixture();
        assertThat(recordMap.resolve(owner.mapToken()).getId()).isEqualTo(owner.mapLink());
        assertThat(travelLinks.map(owner.travelToken()).link().owner()).isEqualTo(owner.owner());
        assertThatThrownBy(() -> tx(() -> suspender.suspend(owner.owner(), LocalDateTime.now())))
                .isInstanceOf(BadRequestException.class);
        tx(() -> users.lockForAccountSecurityUpdate(owner.owner()).orElseThrow().setActive(false));
        assertThatThrownBy(() -> tx(() -> suspender.suspend(owner.owner(), LocalDateTime.now())))
                .isInstanceOf(BadRequestException.class);
        assertPublished(owner, true);

        LocalDateTime now = LocalDateTime.now().withNano(0);
        tx(() -> {
            request(owner.owner());
            suspender.suspend(owner.owner(), now);
        });
        assertPublished(owner, false);
        assertPublished(other, true);
        assertThatThrownBy(() -> recordMap.resolve(owner.mapToken())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> travelLinks.map(owner.travelToken())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> driveLinks.downloadByToken(owner.driveToken())).isInstanceOf(BadRequestException.class);
        assertThat(recordMap.resolve(other.mapToken()).getId()).isEqualTo(other.mapLink());
        assertThat(travelLinks.map(other.travelToken()).link().owner()).isEqualTo(other.owner());
    }

    @Test void suspensionRollsBackWhenTheSurroundingWithdrawalFails() {
        Fixture owner = fixture();
        assertThatThrownBy(() -> tx(() -> {
            users.lockForAccountSecurityUpdate(owner.owner()).orElseThrow().setActive(false);
            request(owner.owner());
            suspender.suspend(owner.owner(), LocalDateTime.now());
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(users.findById(owner.owner()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.owner())).isEmpty();
        assertPublished(owner, true);
    }

    @Test void ordinaryDisabledOwnerRetainsExistingInternalPlanLookupButWithdrawalDoesNot() {
        Fixture owner = fixture();
        tx(() -> users.lockForAccountSecurityUpdate(owner.owner()).orElseThrow().setActive(false));
        tx(() -> planStore.requireOwnedPlan(owner.owner(), owner.plan()));
        assertThat(driveLinks.downloadByToken(owner.driveToken()).fileName()).isEqualTo("file.txt");
        tx(() -> request(owner.owner()));
        assertThatThrownBy(() -> tx(() -> planStore.requireOwnedPlan(owner.owner(), owner.plan())))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> driveLinks.downloadByToken(owner.driveToken())).isInstanceOf(BadRequestException.class);
    }

    @Test void publicDriveAccessWaitingOnWithdrawalCannotMintAnotherDownload() throws Exception {
        Fixture owner = fixture();
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var withdrawal = pool.submit(() -> tx(() -> {
                users.lockForAccountSecurityUpdate(owner.owner()).orElseThrow().setActive(false);
                request(owner.owner());
                suspender.suspend(owner.owner(), LocalDateTime.now());
                entities.flush();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var download = pool.submit(() -> driveLinks.downloadByToken(owner.driveToken()));
            assertThatThrownBy(() -> download.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            withdrawal.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> download.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadRequestException.class);
            assertPublished(owner, false);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void withdrawalWaitsForAnAlreadyStartedDriveAccessAndItsRevocationIsNotOverwritten() throws Exception {
        Fixture owner = fixture();
        var pool = Executors.newFixedThreadPool(2);
        var accessed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var download = pool.submit(() -> tx(() -> {
                assertThat(driveLinks.downloadByToken(owner.driveToken()).fileName()).isEqualTo("file.txt");
                accessed.countDown();
                await(release);
            }));
            assertThat(accessed.await(5, TimeUnit.SECONDS)).isTrue();
            var withdrawal = pool.submit(() -> tx(() -> {
                users.lockForAccountSecurityUpdate(owner.owner()).orElseThrow().setActive(false);
                request(owner.owner());
                suspender.suspend(owner.owner(), LocalDateTime.now());
            }));
            assertThatThrownBy(() -> withdrawal.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            download.get(5, TimeUnit.SECONDS);
            withdrawal.get(5, TimeUnit.SECONDS);
            assertPublished(owner, false);
            assertThatThrownBy(() -> driveLinks.downloadByToken(owner.driveToken())).isInstanceOf(BadRequestException.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void publicShareWritesWaitingOnAccountDeactivationCannotReopenAccess() throws Exception {
        Fixture owner = fixture();
        var pool = Executors.newFixedThreadPool(10);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> tx(() -> {
                users.lockForAccountSecurityUpdate(owner.owner()).orElseThrow().setActive(false);
                request(owner.owner());
                entities.flush();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("deactivation timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            List<Callable<Object>> operations = List.of(
                    () -> recordMap.create(owner.owner(), null, null),
                    () -> travelLinks.create(owner.owner(), null),
                    () -> driveLinks.createLink(owner.owner(), null, null),
                    () -> travel.updatePlanPublicShare(owner.owner(), owner.plan(), true),
                    () -> travelRecords.createMemory(owner.owner(), null, null),
                    () -> travelLedger.toTravel(owner.owner(), null, null),
                    () -> travelPlans.create(owner.owner(), null),
                    () -> travelPlans.update(owner.owner(), owner.plan(), null),
                    () -> { travelPlans.delete(owner.owner(), owner.plan()); return null; });
            var writes = operations.stream().map(pool::submit).toList();
            for (var write : writes) {
                assertThatThrownBy(() -> write.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            }
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            for (var write : writes) {
                assertThatThrownBy(() -> write.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(NotFoundException.class);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private Fixture fixture() {
        return new TransactionTemplate(transactions).execute(status -> {
            var user = new AppUser(); user.setLoginId("public-" + UUID.randomUUID()); user.setDisplayName("소유자");
            entities.persist(user);
            var plan = new TravelPlan(); plan.setOwner(user); plan.setName("여행"); plan.setStartDate(LocalDate.now());
            plan.setEndDate(LocalDate.now()); plan.setStatus(TravelPlanStatus.COMPLETED);
            plan.setPublicShared(true); plan.setPublicSharedAt(LocalDateTime.now()); entities.persist(plan);
            var record = new TravelExpenseRecord(); record.setPlan(plan); record.setTitle("기록"); record.setCategory("장소");
            record.setExpenseDate(LocalDate.now()); record.setAmount(BigDecimal.ZERO); record.setSharedWithCommunity(true); entities.persist(record);
            var travelLink = new TravelMapShareLink(); travelLink.setOwner(user); travelLink.setToken(UUID.randomUUID().toString());
            travelLink.setCreatedAt(LocalDateTime.now()); travelLink.setUpdatedAt(LocalDateTime.now()); entities.persist(travelLink);
            var collection = new PlaceCollection(); collection.setOwnerId(user.getId()); collection.setName("장소 모음"); entities.persist(collection);
            var mapLink = recordMap.create(user.getId(), collection.getId(), new ShareRequest(true, 1));
            var item = new DriveItem(); item.setOwner(user); item.setOriginalName("file.txt"); item.setStoredName("file.txt");
            item.setStoragePath("private/" + UUID.randomUUID()); entities.persist(item);
            var driveLink = new DriveDownloadLink(); driveLink.setOwner(user); driveLink.setItem(item);
            driveLink.setToken(UUID.randomUUID().toString()); driveLink.setExpiresAt(LocalDateTime.now().plusDays(1));
            driveLink.setMaxDownloads(5); entities.persist(driveLink);
            entities.flush();
            return new Fixture(user.getId(), plan.getId(), record.getId(), travelLink.getId(), mapLink.id(), driveLink.getId(),
                    travelLink.getToken(), mapLink.token(), driveLink.getToken());
        });
    }

    private void request(Long owner) {
        var request = new AccountWithdrawalRequest(); request.setId(UUID.randomUUID().toString()); request.setUserId(owner);
        request.setState(AccountWithdrawalRequest.State.IN_PROGRESS); request.setRequestedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now()); requests.saveAndFlush(request);
    }

    private void assertPublished(Fixture f, boolean expected) {
        tx(() -> {
            assertThat(Boolean.TRUE.equals(entities.find(TravelPlan.class, f.plan()).getPublicShared())).isEqualTo(expected);
            assertThat(Boolean.TRUE.equals(entities.find(TravelExpenseRecord.class, f.record()).getSharedWithCommunity())).isEqualTo(expected);
            assertThat(Boolean.TRUE.equals(entities.find(TravelMapShareLink.class, f.travelLink()).getActive())).isEqualTo(expected);
            assertThat(entities.find(RecordMapShare.class, f.mapLink()).isActive()).isEqualTo(expected);
            assertThat(entities.find(DriveDownloadLink.class, f.driveLink()).getRevokedAt() == null).isEqualTo(expected);
        });
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("operation timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void tx(Runnable work) { new TransactionTemplate(transactions).executeWithoutResult(status -> work.run()); }
    private record Fixture(Long owner, Long plan, Long record, Long travelLink, Long mapLink, Long driveLink,
                           String travelToken, String mapToken, String driveToken) {}
}
