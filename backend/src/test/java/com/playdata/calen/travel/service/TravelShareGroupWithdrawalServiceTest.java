package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.sharing.domain.RecordShare;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.RecordShareStatus;
import com.playdata.calen.sharing.repository.RecordShareRepository;
import com.playdata.calen.travel.domain.TravelShareGroup;
import com.playdata.calen.travel.domain.TravelShareGroupMember;
import com.playdata.calen.travel.repository.TravelShareGroupMemberRepository;
import com.playdata.calen.travel.repository.TravelShareGroupRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "app.seed.enabled=true", "app.seed.allow-insecure-default-credentials=true",
        "spring.datasource.url=jdbc:h2:mem:group-withdrawal-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@Transactional
class TravelShareGroupWithdrawalServiceTest {
    @Autowired AppUserRepository users;
    @Autowired TravelShareGroupRepository groups;
    @Autowired TravelShareGroupMemberRepository members;
    @Autowired RecordShareRepository shares;
    @Autowired TravelShareGroupWithdrawalService transfer;
    @Autowired EntityManager entityManager;

    @Test void transfersOwnershipWithoutBreakingOtherMembersShares() {
        AppUser owner = user("hana"), nextOwner = user("minsu"), other = user("admin");
        TravelShareGroup group = group(owner, "외가 가족");
        add(group, nextOwner);
        add(group, other);
        RecordShare existing = share(group, nextOwner, other);
        assertThat(transfer.requiredTransfers(owner.getId())).singleElement().satisfies(choice -> {
            assertThat(choice.groupId()).isEqualTo(group.getId());
            assertThat(choice.containsOthersShares()).isTrue();
            assertThat(choice.members()).filteredOn(TravelShareGroupWithdrawalService.Candidate::eligible)
                    .extracting(TravelShareGroupWithdrawalService.Candidate::userId)
                    .containsExactlyInAnyOrder(nextOwner.getId(), other.getId());
        });
        transfer.transferForWithdrawal(owner.getId(), Map.of(group.getId(), nextOwner.getId()));
        entityManager.flush();
        entityManager.clear();
        assertThat(groups.findById(group.getId()).orElseThrow().getOwner().getId()).isEqualTo(nextOwner.getId());
        assertThat(members.existsByGroupIdAndMemberId(group.getId(), nextOwner.getId())).isFalse();
        assertThat(members.existsByGroupIdAndMemberId(group.getId(), other.getId())).isTrue();
        assertThat(shares.findById(existing.getId())).isPresent();
        assertThat(shares.findRequests(other.getId(), RecordShareKind.LEDGER, false, null,
                List.of(group.getId()), PageRequest.of(0, 10)).getContent())
                .extracting(RecordShare::getId).contains(existing.getId());
    }

    @Test void sameNameCollisionIsReportedAndBlocksTheWholeTransfer() {
        AppUser owner = user("hana"), target = user("minsu");
        TravelShareGroup group = group(owner, "Family");
        add(group, target);
        group(target, "family");
        var choice = transfer.requiredTransfers(owner.getId()).get(0);
        assertThat(choice.members()).singleElement().satisfies(candidate -> {
            assertThat(candidate.eligible()).isFalse();
            assertThat(candidate.unavailableReason()).contains("같은 이름");
        });
        assertThatThrownBy(() -> transfer.transferForWithdrawal(owner.getId(), Map.of(group.getId(), target.getId())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("같은 이름");
        assertThat(group.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(members.existsByGroupIdAndMemberId(group.getId(), target.getId())).isTrue();
    }

    @Test void inactiveOrNonMemberTargetsCannotReceiveTheGroup() {
        AppUser owner = user("hana"), target = user("minsu"), outsider = user("admin");
        TravelShareGroup group = group(owner, "여행");
        add(group, target);
        target.setActive(false);
        users.saveAndFlush(target);
        assertThat(transfer.requiredTransfers(owner.getId()).get(0).members()).singleElement()
                .satisfies(candidate -> assertThat(candidate.eligible()).isFalse());
        assertThatThrownBy(() -> transfer.transferForWithdrawal(owner.getId(), Map.of(group.getId(), target.getId())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> transfer.transferForWithdrawal(owner.getId(), Map.of(group.getId(), outsider.getId())))
                .isInstanceOf(BadRequestException.class);
        assertThat(group.getOwner().getId()).isEqualTo(owner.getId());
    }

    @Test void groupWithOtherUsersSharesButNoMembersCannotBeDiscardedSilently() {
        AppUser owner = user("hana"), sender = user("minsu"), recipient = user("admin");
        TravelShareGroup group = group(owner, "이전 멤버 기록");
        RecordShare otherShare = share(group, sender, recipient);
        assertThat(transfer.requiredTransfers(owner.getId())).singleElement().satisfies(choice -> {
            assertThat(choice.containsOthersShares()).isTrue();
            assertThat(choice.members()).isEmpty();
        });
        assertThatThrownBy(() -> transfer.transferForWithdrawal(owner.getId(), Map.of()))
                .isInstanceOf(BadRequestException.class);
        assertThat(shares.findById(otherShare.getId())).isPresent();
    }

    @Test void invalidChoiceDoesNotPartiallyTransferOtherGroups() {
        AppUser owner = user("hana"), target = user("minsu"), outsider = user("admin");
        TravelShareGroup first = group(owner, "첫 그룹"), second = group(owner, "둘째 그룹");
        add(first, target);
        add(second, target);
        assertThatThrownBy(() -> transfer.transferForWithdrawal(owner.getId(),
                Map.of(first.getId(), target.getId(), second.getId(), outsider.getId())))
                .isInstanceOf(BadRequestException.class);
        assertThat(first.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(second.getOwner().getId()).isEqualTo(owner.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void requiresTheCallersTransaction() {
        assertThatThrownBy(() -> transfer.transferForWithdrawal(1L, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private AppUser user(String login) { return users.findByLoginId(login).orElseThrow(); }
    private TravelShareGroup group(AppUser owner, String name) {
        TravelShareGroup group = new TravelShareGroup();
        group.setOwner(owner);
        group.setName(name);
        group.setCreatedAt(LocalDateTime.now());
        group.setUpdatedAt(LocalDateTime.now());
        return groups.saveAndFlush(group);
    }
    private void add(TravelShareGroup group, AppUser user) {
        TravelShareGroupMember member = new TravelShareGroupMember();
        member.setGroup(group);
        member.setMember(user);
        member.setCreatedAt(LocalDateTime.now());
        members.saveAndFlush(member);
    }
    private RecordShare share(TravelShareGroup group, AppUser sender, AppUser recipient) {
        RecordShare share = new RecordShare();
        share.setGroupId(group.getId());
        share.setGroupName(group.getName());
        share.setKind(RecordShareKind.LEDGER);
        share.setSourceId(101L);
        share.setSender(sender);
        share.setRecipient(recipient);
        share.setStatus(RecordShareStatus.PENDING);
        share.setTitle("다른 사용자들의 공유 거래");
        share.setCreatedAt(LocalDateTime.now());
        return shares.saveAndFlush(share);
    }
}
