package com.playdata.calen.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequest;
import com.playdata.calen.account.withdrawal.AccountWithdrawalRequestRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import com.playdata.calen.sharing.domain.RecordShare;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.RecordShareStatus;
import com.playdata.calen.sharing.repository.RecordShareRepository;
import com.playdata.calen.sharing.service.RecordShareWithdrawalService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "app.seed.enabled=true", "app.seed.allow-insecure-default-credentials=true",
        "spring.datasource.url=jdbc:h2:mem:share-withdrawal-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
@Transactional
class RecordShareWithdrawalServiceTest {
    @Autowired AppUserRepository users;
    @Autowired CategoryGroupRepository categories;
    @Autowired PaymentMethodRepository payments;
    @Autowired LedgerEntryRepository entries;
    @Autowired RecordShareRepository shares;
    @Autowired RecordShareWithdrawalService cleanup;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired EntityManager entityManager;

    @Test void removesOnlyTheDepartingUsersEnvelopesAndPreservesRecipientsIndependentLedgerEntry() {
        AppUser sender = user("hana");
        AppUser recipient = user("minsu");
        AppUser other = user("admin");
        LedgerEntry imported = recipientEntry(recipient);
        RecordShare accepted = share(sender, recipient, 1L, RecordShareStatus.ACCEPTED);
        accepted.setImportedLedgerEntryId(imported.getId());
        accepted.setSnapshotJson("{\"amount\":15000}");
        accepted.setShareMemo("공유 메모");
        shares.saveAndFlush(accepted);
        RecordShare received = share(other, sender, 2L, RecordShareStatus.PENDING);
        RecordShare unrelated = share(recipient, other, 3L, RecordShareStatus.ACCEPTED);
        Long acceptedId = accepted.getId(), receivedId = received.getId(), unrelatedId = unrelated.getId();
        Long importedId = imported.getId();
        sender.setActive(false);
        users.saveAndFlush(sender);
        var request = new AccountWithdrawalRequest(); request.setId(UUID.randomUUID().toString()); request.setUserId(sender.getId());
        request.setState(AccountWithdrawalRequest.State.IN_PROGRESS); request.setRequestedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now()); requests.saveAndFlush(request);

        assertThat(cleanup.removeEnvelopesForInactiveUser(sender.getId())).isEqualTo(2);
        assertThat(cleanup.removeEnvelopesForInactiveUser(sender.getId())).isZero();
        entityManager.flush();
        entityManager.clear();
        assertThat(shares.findById(acceptedId)).isEmpty();
        assertThat(shares.findById(receivedId)).isEmpty();
        assertThat(shares.findById(unrelatedId)).isPresent();
        LedgerEntry preserved = entries.findById(importedId).orElseThrow();
        assertThat(preserved.getOwner().getId()).isEqualTo(recipient.getId());
        assertThat(preserved.getTitle()).isEqualTo("받는 사람이 등록한 거래");
        assertThat(preserved.getAmount()).isEqualByComparingTo("7500");
    }

    @Test void rejectsAnActiveAccountBeforeTouchingItsShares() {
        AppUser sender = user("hana");
        RecordShare pending = share(sender, user("minsu"), 4L, RecordShareStatus.PENDING);
        assertThatThrownBy(() -> cleanup.removeEnvelopesForInactiveUser(sender.getId()))
                .isInstanceOf(BadRequestException.class);
        assertThat(shares.findById(pending.getId())).isPresent();
    }

    @Test void rejectsAnOrdinarilyDisabledAccountWithoutAWithdrawalRequest() {
        AppUser sender = user("hana");
        RecordShare pending = share(sender, user("minsu"), 5L, RecordShareStatus.PENDING);
        sender.setActive(false); users.saveAndFlush(sender);
        assertThatThrownBy(() -> cleanup.removeEnvelopesForInactiveUser(sender.getId()))
                .isInstanceOf(BadRequestException.class);
        assertThat(shares.findById(pending.getId())).isPresent();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void requiresTheCallersTransaction() {
        assertThatThrownBy(() -> cleanup.removeEnvelopesForInactiveUser(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private AppUser user(String loginId) { return users.findByLoginId(loginId).orElseThrow(); }
    private RecordShare share(AppUser sender, AppUser recipient, Long source, RecordShareStatus status) {
        RecordShare share = new RecordShare();
        share.setGroupId(42L);
        share.setGroupName("가족");
        share.setKind(RecordShareKind.LEDGER);
        share.setSourceId(source);
        share.setSender(sender);
        share.setRecipient(recipient);
        share.setStatus(status);
        share.setTitle("공유 거래");
        share.setCreatedAt(LocalDateTime.now());
        return shares.saveAndFlush(share);
    }
    private LedgerEntry recipientEntry(AppUser owner) {
        CategoryGroup category = new CategoryGroup();
        category.setOwner(owner);
        category.setName("받는 사람의 식비");
        category.setEntryType(EntryType.EXPENSE);
        categories.saveAndFlush(category);
        PaymentMethod payment = new PaymentMethod();
        payment.setOwner(owner);
        payment.setName("받는 사람의 카드");
        payment.setKind(PaymentMethodKind.CARD);
        payments.saveAndFlush(payment);
        LedgerEntry entry = new LedgerEntry();
        entry.setOwner(owner);
        entry.setEntryDate(LocalDate.now());
        entry.setTitle("받는 사람이 등록한 거래");
        entry.setAmount(new BigDecimal("7500"));
        entry.setEntryType(EntryType.EXPENSE);
        entry.setCategoryGroup(category);
        entry.setPaymentMethod(payment);
        return entries.saveAndFlush(entry);
    }
}
