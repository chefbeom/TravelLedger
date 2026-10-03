package com.playdata.calen.sharing.repository;

import static org.assertj.core.api.Assertions.*;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.sharing.domain.*;
import com.playdata.calen.travel.domain.TravelShareGroup;
import com.playdata.calen.travel.domain.TravelShareGroupMember;
import com.playdata.calen.travel.repository.TravelShareGroupRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop", "app.schema.legacy-updaters.enabled=false"})
class RecordShareRepositoryTest {
    @Autowired TestEntityManager em;
    @Autowired RecordShareRepository repository;
    @Autowired TravelShareGroupRepository groups;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void persistsInboxAndCountsWithRecipientGroupStatusAndKindScope() {
        AppUser sender = user("sender"), receiver = user("receiver"), outsider = user("outsider");
        var group = group(sender, receiver);
        var first = share(sender, receiver, group.getId(), 1L, RecordShareKind.LEDGER);
        var second = share(sender, receiver, group.getId(), 2L, RecordShareKind.LEDGER);
        share(sender, outsider, group.getId(), 3L, RecordShareKind.LEDGER);
        share(sender, receiver, 999L, 4L, RecordShareKind.LEDGER);
        share(sender, receiver, group.getId(), 5L, RecordShareKind.TRAVEL);
        em.flush(); em.clear();
        var page = repository.findRequests(receiver.getId(), RecordShareKind.LEDGER, false, RecordShareStatus.PENDING,
                List.of(group.getId()), PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "id")));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(RecordShare::getId).containsExactly(second.getId());
        assertThat(repository.countPending(receiver.getId(), RecordShareKind.LEDGER, List.of(group.getId()))).isEqualTo(2);
        assertThat(repository.findLockedById(first.getId())).isPresent();
        assertThat(groups.findAccessibleGroups(receiver.getId())).extracting(TravelShareGroup::getId).containsExactly(group.getId());
        assertThat(groups.findAccessibleGroups(outsider.getId())).isEmpty();
        assertThat(repository.findRequests(sender.getId(), RecordShareKind.LEDGER, true, null, List.of(group.getId()), PageRequest.of(0, 10)).getTotalElements()).isEqualTo(3);
    }

    @Test void sameSourceCannotBeImportedAgainThroughAnotherGroup() {
        AppUser sender = user("sender"), receiver = user("receiver");
        share(sender, receiver, 1L, 7L, RecordShareKind.LEDGER);
        assertThatThrownBy(() -> { share(sender, receiver, 2L, 7L, RecordShareKind.LEDGER); repository.flush(); })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void departedSenderRequestsDisappearFromInboxAndNotificationCount() {
        AppUser sender = user("sender"), receiver = user("receiver");
        var group = group(receiver, sender);
        share(sender, receiver, group.getId(), 1L, RecordShareKind.LEDGER);
        em.flush();
        em.getEntityManager().createQuery("delete from TravelShareGroupMember m where m.group.id = :group and m.member.id = :sender")
                .setParameter("group", group.getId()).setParameter("sender", sender.getId()).executeUpdate();
        em.clear();
        assertThat(repository.countPending(receiver.getId(), RecordShareKind.LEDGER, List.of(group.getId()))).isZero();
        assertThat(repository.findRequests(receiver.getId(), RecordShareKind.LEDGER, false, null,
                List.of(group.getId()), PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reshareLookupWaitsForApprovalAndReadsTheCommittedAcceptedStatus() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long[] ids = tx.execute(status -> {
            String suffix = UUID.randomUUID().toString();
            AppUser sender = user("lock-sender-" + suffix), receiver = user("lock-receiver-" + suffix);
            RecordShare request = share(sender, receiver, 99L, 100L, RecordShareKind.LEDGER);
            em.flush();
            return new Long[] {request.getId(), sender.getId(), receiver.getId()};
        });
        var executor = Executors.newFixedThreadPool(2);
        var approvedRowLocked = new CountDownLatch(1);
        var releaseApproval = new CountDownLatch(1);
        var reshareStarted = new CountDownLatch(1);
        try {
            var approval = executor.submit(() -> tx.execute(status -> {
                var request = repository.findLockedById(ids[0]).orElseThrow();
                request.setStatus(RecordShareStatus.ACCEPTED);
                request.setImportedLedgerEntryId(300L);
                approvedRowLocked.countDown();
                try {
                    if (!releaseApproval.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test approval was not released.");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Test approval interrupted.", ex);
                }
                return request.getStatus();
            }));
            assertThat(approvedRowLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var reshare = executor.submit(() -> tx.execute(status -> {
                reshareStarted.countDown();
                var request = repository.findLockedByKindAndSourceIdAndRecipientId(RecordShareKind.LEDGER, 100L, ids[2]).orElseThrow();
                return new Object[] {request.getStatus(), request.getImportedLedgerEntryId()};
            }));
            assertThat(reshareStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> reshare.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseApproval.countDown();
            assertThat(approval.get(5, TimeUnit.SECONDS)).isEqualTo(RecordShareStatus.ACCEPTED);
            assertThat(reshare.get(5, TimeUnit.SECONDS)).containsExactly(RecordShareStatus.ACCEPTED, 300L);
        } finally {
            releaseApproval.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            tx.executeWithoutResult(status -> {
                repository.deleteById(ids[0]);
                em.flush();
                em.remove(em.find(AppUser.class, ids[1]));
                em.remove(em.find(AppUser.class, ids[2]));
            });
        }
    }

    private AppUser user(String name) { AppUser user = new AppUser(); user.setLoginId(name); user.setDisplayName(name); user.setPasswordHash("test-only-hash"); return em.persist(user); }
    private TravelShareGroup group(AppUser owner, AppUser member) {
        var group = new TravelShareGroup(); group.setOwner(owner); group.setName("family"); group.setCreatedAt(LocalDateTime.now()); group.setUpdatedAt(LocalDateTime.now()); em.persist(group);
        var membership = new TravelShareGroupMember(); membership.setGroup(group); membership.setMember(member); membership.setCreatedAt(LocalDateTime.now()); em.persist(membership); return group;
    }
    private RecordShare share(AppUser sender, AppUser receiver, Long groupId, Long sourceId, RecordShareKind kind) {
        var share = new RecordShare(); share.setSender(sender); share.setRecipient(receiver); share.setGroupId(groupId); share.setGroupName("family"); share.setSourceId(sourceId); share.setKind(kind); share.setTitle("record"); share.setCreatedAt(LocalDateTime.now()); return repository.save(share);
    }
}
