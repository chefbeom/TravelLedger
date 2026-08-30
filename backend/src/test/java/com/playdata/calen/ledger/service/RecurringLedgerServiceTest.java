package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.RecurringLedgerMode;
import com.playdata.calen.ledger.domain.RecurringLedgerOccurrence;
import com.playdata.calen.ledger.domain.RecurringLedgerOccurrenceStatus;
import com.playdata.calen.ledger.domain.RecurringLedgerRule;
import com.playdata.calen.ledger.domain.RecurringLedgerScheduleType;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.dto.RecurringLedgerOccurrenceResponse;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import com.playdata.calen.ledger.repository.RecurringLedgerOccurrenceRepository;
import com.playdata.calen.ledger.repository.RecurringLedgerRuleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecurringLedgerServiceTest {

    @Mock
    private AppUserService appUserService;

    @Mock
    private RecurringLedgerRuleRepository ruleRepository;

    @Mock
    private RecurringLedgerOccurrenceRepository occurrenceRepository;

    @Mock
    private CategoryGroupRepository categoryGroupRepository;

    @Mock
    private CategoryDetailRepository categoryDetailRepository;

    @Mock
    private PaymentMethodRepository paymentMethodRepository;

    @Mock
    private LedgerEntryService ledgerEntryService;

    @Test
    void autoRuleCreatesOneEntryPerScheduledDate() {
        LocalDate date = LocalDate.of(2045, 4, 15);
        RecurringLedgerRule rule = recurringRule(RecurringLedgerMode.AUTO, date);
        AtomicReference<RecurringLedgerOccurrence> storedOccurrence = new AtomicReference<>();
        stubDueRule(rule, date, storedOccurrence);

        LedgerEntryResponse createdEntry = mock(LedgerEntryResponse.class);
        when(createdEntry.id()).thenReturn(900L);
        when(ledgerEntryService.create(eq(7L), any())).thenReturn(createdEntry);

        RecurringLedgerService service = service();

        assertThat(service.processDueDate(date)).isEqualTo(1);
        assertThat(service.processDueDate(date)).isZero();

        verify(ledgerEntryService, times(1)).create(eq(7L), any());
        verify(occurrenceRepository, times(1)).save(storedOccurrence.get());
        assertThat(storedOccurrence.get().getStatus()).isEqualTo(RecurringLedgerOccurrenceStatus.CREATED);
        assertThat(storedOccurrence.get().getCreatedEntryId()).isEqualTo(900L);
    }

    @Test
    void everyNDaysRuleUsesTheIntervalWhenProcessingDueDate() {
        LocalDate date = LocalDate.of(2045, 4, 15);
        RecurringLedgerRule rule = recurringRule(RecurringLedgerMode.AUTO, date);
        rule.setScheduleType(RecurringLedgerScheduleType.EVERY_N_DAYS);
        rule.setDayOfMonth(null);
        rule.setIntervalDays(23);
        rule.setStartDate(date.minusDays(23));
        AtomicReference<RecurringLedgerOccurrence> storedOccurrence = new AtomicReference<>();
        stubDueRule(rule, date, storedOccurrence);

        LedgerEntryResponse createdEntry = mock(LedgerEntryResponse.class);
        when(createdEntry.id()).thenReturn(901L);
        when(ledgerEntryService.create(eq(7L), any())).thenReturn(createdEntry);

        assertThat(service().processDueDate(date)).isEqualTo(1);

        verify(ledgerEntryService, times(1)).create(eq(7L), any());
        assertThat(storedOccurrence.get().getStatus()).isEqualTo(RecurringLedgerOccurrenceStatus.CREATED);
        assertThat(storedOccurrence.get().getCreatedEntryId()).isEqualTo(901L);
    }

    @Test
    void confirmationRuleWaitsForApprovalWithoutCreatingEntry() {
        LocalDate date = LocalDate.of(2045, 4, 15);
        RecurringLedgerRule rule = recurringRule(RecurringLedgerMode.CONFIRM, date);
        AtomicReference<RecurringLedgerOccurrence> storedOccurrence = new AtomicReference<>();
        stubDueRule(rule, date, storedOccurrence);

        RecurringLedgerService service = service();

        assertThat(service.processDueDate(date)).isEqualTo(1);

        verifyNoInteractions(ledgerEntryService);
        assertThat(storedOccurrence.get().getMode()).isEqualTo(RecurringLedgerMode.CONFIRM);
        assertThat(storedOccurrence.get().getStatus()).isEqualTo(RecurringLedgerOccurrenceStatus.PENDING);
    }
    @Test
    void approvingConfirmationOccurrenceCreatesTheExistingLedgerEntry() {
        LocalDate date = LocalDate.of(2045, 4, 15);
        RecurringLedgerRule rule = recurringRule(RecurringLedgerMode.CONFIRM, date);
        RecurringLedgerOccurrence occurrence = new RecurringLedgerOccurrence();
        occurrence.setId(41L);
        occurrence.setRule(rule);
        occurrence.setScheduledDate(date);
        occurrence.setMode(RecurringLedgerMode.CONFIRM);
        occurrence.setStatus(RecurringLedgerOccurrenceStatus.PENDING);
        when(occurrenceRepository.findByIdAndRuleOwnerId(41L, 7L)).thenReturn(Optional.of(occurrence));

        LedgerEntryResponse createdEntry = mock(LedgerEntryResponse.class);
        when(createdEntry.id()).thenReturn(900L);
        when(ledgerEntryService.create(eq(7L), any())).thenReturn(createdEntry);

        RecurringLedgerOccurrenceResponse response = service().approveOccurrence(7L, 41L);

        verify(ledgerEntryService, times(1)).create(eq(7L), any());
        assertThat(response.status()).isEqualTo(RecurringLedgerOccurrenceStatus.CREATED);
        assertThat(response.createdEntryId()).isEqualTo(900L);
        assertThat(occurrence.getProcessedAt()).isNotNull();
    }

    @Test
    void activeRuleMustBePausedBeforeItCanBeDeleted() {
        RecurringLedgerRule rule = recurringRule(RecurringLedgerMode.AUTO, LocalDate.of(2045, 4, 15));
        when(ruleRepository.findByIdAndOwnerId(31L, 7L)).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service().deleteRule(7L, 31L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("사용 중인 정기 입출금은 먼저 일시정지해 주세요.");

        verify(occurrenceRepository, never()).deleteAllByRuleId(anyLong());
        verify(ruleRepository, never()).delete(any(RecurringLedgerRule.class));
    }

    @Test
    void pausedRuleDeletesItsOccurrencesBeforeDeletingTheRule() {
        RecurringLedgerRule rule = recurringRule(RecurringLedgerMode.AUTO, LocalDate.of(2045, 4, 15));
        rule.setActive(false);
        when(ruleRepository.findByIdAndOwnerId(31L, 7L)).thenReturn(Optional.of(rule));

        service().deleteRule(7L, 31L);

        verify(occurrenceRepository).deleteAllByRuleId(31L);
        verify(ruleRepository).delete(rule);
    }


    private RecurringLedgerService service() {
        return new RecurringLedgerService(
                appUserService,
                ruleRepository,
                occurrenceRepository,
                categoryGroupRepository,
                categoryDetailRepository,
                paymentMethodRepository,
                ledgerEntryService
        );
    }

    private void stubDueRule(
            RecurringLedgerRule rule,
            LocalDate date,
            AtomicReference<RecurringLedgerOccurrence> storedOccurrence
    ) {
        when(ruleRepository.findAllByActiveTrueAndStartDateLessThanEqual(date)).thenReturn(List.of(rule));
        when(occurrenceRepository.findByRuleIdAndScheduledDate(rule.getId(), date))
                .thenAnswer(invocation -> Optional.ofNullable(storedOccurrence.get()));
        when(occurrenceRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            RecurringLedgerOccurrence occurrence = invocation.getArgument(0);
            occurrence.setId(41L);
            storedOccurrence.set(occurrence);
            return occurrence;
        });
    }

    private RecurringLedgerRule recurringRule(RecurringLedgerMode mode, LocalDate date) {
        AppUser owner = new AppUser();
        owner.setId(7L);

        CategoryGroup categoryGroup = new CategoryGroup();
        categoryGroup.setId(11L);
        categoryGroup.setName("고정비");
        categoryGroup.setEntryType(EntryType.EXPENSE);

        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setId(21L);
        paymentMethod.setName("체크카드");

        RecurringLedgerRule rule = new RecurringLedgerRule();
        rule.setId(31L);
        rule.setOwner(owner);
        rule.setTitle("정기 구독");
        rule.setMemo("테스트");
        rule.setAmount(BigDecimal.valueOf(12000));
        rule.setEntryType(EntryType.EXPENSE);
        rule.setDayOfMonth(date.getDayOfMonth());
        rule.setStartDate(date.minusMonths(1));
        rule.setMode(mode);
        rule.setCategoryGroup(categoryGroup);
        rule.setPaymentMethod(paymentMethod);
        rule.setActive(true);
        return rule;
    }
}
