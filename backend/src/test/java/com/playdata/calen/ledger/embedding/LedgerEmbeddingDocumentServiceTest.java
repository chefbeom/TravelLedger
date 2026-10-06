package com.playdata.calen.ledger.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.domain.RecurringLedgerMode;
import com.playdata.calen.ledger.domain.RecurringLedgerRule;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import com.playdata.calen.ledger.repository.RecurringLedgerRuleRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false", "app.schema.legacy-updaters.enabled=false"})
@Import({LedgerEmbeddingDocumentFactory.class, LedgerEmbeddingDocumentService.class})
class LedgerEmbeddingDocumentServiceTest {

    @Autowired private AppUserRepository users;
    @Autowired private LedgerEntryRepository entries;
    @Autowired private RecurringLedgerRuleRepository rules;
    @Autowired private CategoryGroupRepository groups;
    @Autowired private CategoryDetailRepository details;
    @Autowired private PaymentMethodRepository payments;
    @Autowired private LedgerImageAnalysisRequestRepository images;
    @Autowired private EntityManager entityManager;
    @Autowired private LedgerEmbeddingDocumentService service;
    private AppUser owner;
    private AppUser otherOwner;
    private CategoryGroup group;
    private CategoryDetail detail;
    private PaymentMethod payment;

    @BeforeEach void setUp() {
        owner = user();
        otherOwner = user();
        group = new CategoryGroup();
        group.setOwner(owner);
        group.setName("Synthetic group");
        group.setEntryType(EntryType.EXPENSE);
        group = groups.saveAndFlush(group);
        detail = new CategoryDetail();
        detail.setGroup(group);
        detail.setName("Synthetic detail");
        detail = details.saveAndFlush(detail);
        payment = new PaymentMethod();
        payment.setOwner(owner);
        payment.setName("Synthetic payment");
        payment.setKind(PaymentMethodKind.CARD);
        payment = payments.saveAndFlush(payment);
    }

    @Test void readsPersistedSourceAndLazyNamesUsingDatabaseOwner() {
        LedgerEntry entry = entries.saveAndFlush(entry());
        Long ownerId = owner.getId();
        Long sourceId = entry.getId();
        entityManager.clear();
        var document = service.prepareEntry(ownerId, sourceId);
        assertThat(document.metadata()).containsEntry("owner_id", ownerId).containsEntry("source_id", sourceId);
        assertThat(document.text()).contains("대분류: Synthetic group", "소분류: Synthetic detail").endsWith("\n메모: ");
    }

    @Test void rejectsAnotherOwnersEntryAndMissingSource() {
        LedgerEntry entry = entries.saveAndFlush(entry());
        assertThatThrownBy(() -> service.prepareEntry(otherOwner.getId(), entry.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.prepareEntry(owner.getId(), Long.MAX_VALUE)).isInstanceOf(NotFoundException.class);
    }

    @Test void excludesSoftDeletedEntryEvenWhenItsOwnerMatches() {
        LedgerEntry entry = entry();
        entry.setDeletedAt(LocalDateTime.of(2026, 10, 5, 0, 0));
        entry = entries.saveAndFlush(entry);
        Long sourceId = entry.getId();
        assertThatThrownBy(() -> service.prepareEntry(owner.getId(), sourceId)).isInstanceOf(NotFoundException.class);
    }

    @Test void readsInactiveRuleWithoutGeneratedTransactionsButRejectsOtherOwner() {
        RecurringLedgerRule rule = new RecurringLedgerRule();
        rule.setOwner(owner);
        rule.setTitle("Synthetic recurring rule");
        rule.setEntryType(EntryType.EXPENSE);
        rule.setAmount(new BigDecimal("4500.00"));
        rule.setCategoryGroup(group);
        rule.setMode(RecurringLedgerMode.CONFIRM);
        rule.setDayOfMonth(15);
        rule.setMonthInterval(1);
        rule.setStartDate(LocalDate.of(2026, 10, 5));
        rule.setActive(false);
        rule = rules.saveAndFlush(rule);
        Long sourceId = rule.getId();
        assertThat(entries.count()).isZero();
        assertThat(service.prepareRule(owner.getId(), sourceId).metadata()).containsEntry("active", false)
                .containsEntry("record_type", "recurring_rule").containsEntry("owner_id", owner.getId());
        assertThatThrownBy(() -> service.prepareRule(otherOwner.getId(), sourceId)).isInstanceOf(NotFoundException.class);
    }

    @Test void completedOcrCandidatesAreNotPersistedLedgerEntries() {
        LedgerImageAnalysisRequest candidate = new LedgerImageAnalysisRequest();
        candidate.setOwner(owner);
        candidate.setStatus(LedgerImageAnalysisStatus.COMPLETED);
        candidate.setResultJson("{\"synthetic_unapproved_candidate\":true}");
        candidate = images.saveAndFlush(candidate);
        Long candidateId = candidate.getId();
        assertThat(entries.count()).isZero();
        assertThatThrownBy(() -> service.prepareEntry(owner.getId(), candidateId)).isInstanceOf(NotFoundException.class);
    }

    @Test void rejectsNullOrNonPositiveInputBeforeLookingUpSource() {
        assertThatThrownBy(() -> service.prepareEntry(null, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.prepareEntry(owner.getId(), 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.prepareRule(-1L, 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    private AppUser user() {
        AppUser user = new AppUser();
        user.setLoginId("embedding-fixture-" + UUID.randomUUID());
        user.setDisplayName("Synthetic embedding fixture");
        user.setPasswordHash("synthetic-not-a-real-password-hash");
        return users.saveAndFlush(user);
    }

    private LedgerEntry entry() {
        LedgerEntry entry = new LedgerEntry();
        entry.setOwner(owner);
        entry.setEntryDate(LocalDate.of(2026, 10, 5));
        entry.setTitle("Synthetic persisted entry");
        entry.setAmount(new BigDecimal("1000.00"));
        entry.setEntryType(EntryType.EXPENSE);
        entry.setCategoryGroup(group);
        entry.setCategoryDetail(detail);
        entry.setPaymentMethod(payment);
        return entry;
    }
}
