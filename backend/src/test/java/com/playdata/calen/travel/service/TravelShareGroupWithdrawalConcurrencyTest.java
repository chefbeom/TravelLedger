package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.sharing.application.port.out.ShareParticipantsPort;
import com.playdata.calen.travel.application.port.in.ManageTravelSharingUseCase;
import com.playdata.calen.travel.application.port.in.ManageOwnedTravelMediaUseCase;
import com.playdata.calen.travel.application.port.in.ManageTravelRouteFilesUseCase;
import com.playdata.calen.travel.application.port.in.WriteTravelRouteUseCase;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.domain.TravelShareGroup;
import com.playdata.calen.travel.domain.TravelShareGroupMember;
import com.playdata.calen.travel.repository.TravelShareGroupMemberRepository;
import com.playdata.calen.travel.repository.TravelShareGroupRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:group-transfer-race-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class TravelShareGroupWithdrawalConcurrencyTest {
    @Autowired AppUserRepository users;
    @Autowired TravelShareGroupRepository groups;
    @Autowired TravelShareGroupMemberRepository members;
    @Autowired TravelShareGroupWithdrawalService transfer;
    @Autowired ManageTravelSharingUseCase sharing;
    @Autowired ManageOwnedTravelMediaUseCase media;
    @Autowired ManageTravelRouteFilesUseCase routeFiles;
    @Autowired WriteTravelRouteUseCase routes;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ShareParticipantsPort participants;

    @Test void inFlightMediaAndRouteWritesCannotProceedAfterAccountDeactivation() throws Exception {
        AppUser owner = user();
        var pool = Executors.newFixedThreadPool(4);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser current = users.lockForAccountSecurityUpdate(owner.getId()).orElseThrow();
                current.setActive(false);
                users.saveAndFlush(current);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("deactivation timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var preparePhoto = pool.submit(() -> media.prepare(owner.getId(), 999L, TravelRecordType.MEMORY, List.of()));
            var prepareRoute = pool.submit(() -> routeFiles.prepare(owner.getId(), 999L, List.of()));
            var editRoute = pool.submit(() -> routes.create(owner.getId(), 999L, null));
            assertThatThrownBy(() -> preparePhoto.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> prepareRoute.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> editRoute.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> preparePhoto.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> prepareRoute.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> editRoute.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(NotFoundException.class);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void inFlightGroupEditsAndDeletionCannotUndoWithdrawalTransfer() throws Exception {
        Fixture f = new TransactionTemplate(transactions).execute(status -> {
            AppUser owner = user();
            AppUser target = user();
            TravelShareGroup group = new TravelShareGroup();
            group.setOwner(owner);
            group.setName("동시 편집 그룹");
            group.setCreatedAt(LocalDateTime.now());
            group.setUpdatedAt(LocalDateTime.now());
            groups.saveAndFlush(group);
            TravelShareGroupMember membership = new TravelShareGroupMember();
            membership.setGroup(group);
            membership.setMember(target);
            membership.setCreatedAt(LocalDateTime.now());
            members.saveAndFlush(membership);
            return new Fixture(owner.getId(), target.getId(), target.getLoginId(), group.getId());
        });
        var pool = Executors.newFixedThreadPool(3);
        var transferred = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var withdrawal = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser locked = users.lockForAccountSecurityUpdate(f.ownerId()).orElseThrow();
                locked.setActive(false);
                transfer.transferForWithdrawal(f.ownerId(), Map.of(f.groupId(), f.targetId()));
                transferred.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("transfer timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(transferred.await(5, TimeUnit.SECONDS)).isTrue();
            var edit = pool.submit(() -> sharing.saveGroup(f.ownerId(), "동시 편집 그룹", List.of(f.targetLogin())));
            var delete = pool.submit(() -> { sharing.deleteGroup(f.ownerId(), f.groupId()); return null; });
            assertThatThrownBy(() -> edit.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> delete.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            withdrawal.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> edit.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> delete.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThat(groups.findById(f.groupId()).orElseThrow().getOwner().getId()).isEqualTo(f.targetId());
            assertThat(groups.findAllByOwnerIdOrderByUpdatedAtDescIdDesc(f.ownerId())).isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void aRemovedMemberCannotCreateAShareFromARequestThatWasAlreadyWaiting() throws Exception {
        var f = new TransactionTemplate(transactions).execute(status -> {
            AppUser owner = user(), member = user(), retained = user();
            var group = new TravelShareGroup(); group.setOwner(owner); group.setName("그룹 권한 경합");
            group.setCreatedAt(LocalDateTime.now()); group.setUpdatedAt(LocalDateTime.now()); groups.saveAndFlush(group);
            var first = new TravelShareGroupMember(); first.setGroup(group); first.setMember(member);
            first.setCreatedAt(LocalDateTime.now()); members.saveAndFlush(first);
            var second = new TravelShareGroupMember(); second.setGroup(group); second.setMember(retained);
            second.setCreatedAt(LocalDateTime.now()); members.saveAndFlush(second);
            return new MembershipFixture(group.getId(), member.getId(), retained.getId(), first.getId());
        });
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var removal = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                groups.findForShareUpdate(f.groupId()).orElseThrow();
                members.deleteById(f.membershipId()); members.flush(); locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("membership removal timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var access = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                participants.lockActiveActors(f.memberId(), List.of());
                return participants.requireGroupAccess(f.groupId(), f.memberId());
            }));
            assertThatThrownBy(() -> access.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); removal.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> access.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThat(groups.findById(f.groupId())).isPresent();
            assertThat(members.findAllByGroupIdOrderByIdAsc(f.groupId()))
                    .extracting(member -> member.getMember().getId()).containsExactly(f.retainedId());
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    private AppUser user() {
        AppUser user = new AppUser();
        user.setLoginId("group_" + UUID.randomUUID());
        user.setDisplayName("테스트 사용자");
        return users.saveAndFlush(user);
    }

    private record Fixture(Long ownerId, Long targetId, String targetLogin, Long groupId) {}
    private record MembershipFixture(Long groupId, Long memberId, Long retainedId, Long membershipId) {}
}
