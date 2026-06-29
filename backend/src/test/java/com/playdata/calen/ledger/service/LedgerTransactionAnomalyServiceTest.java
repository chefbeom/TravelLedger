package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerTransactionAnomalyServiceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);

    @Mock
    private AppUserService appUserService;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private LedgerTransactionAnomalyService service;

    @BeforeEach
    void setUp() {
        service = new LedgerTransactionAnomalyService(appUserService, ledgerEntryRepository);
    }

    @Test
    void findAnomaliesGroupsSameDaySameAmountNormalizedTitleExpensesOnly() {
        stubUser();
        List<LedgerEntry> entries = List.of(
                entry(1L, LocalDate.of(2026, 6, 10), "Coffee   Shop", "4500.00", EntryType.EXPENSE),
                entry(2L, LocalDate.of(2026, 6, 10), " coffee shop ", "4500.0", EntryType.EXPENSE),
                entry(3L, LocalDate.of(2026, 6, 10), "coffee shop", "4500.00", EntryType.INCOME),
                entry(4L, LocalDate.of(2026, 6, 11), "coffee shop", "4500.00", EntryType.EXPENSE)
        );
        when(ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(USER_ID, FROM, TO))
                .thenReturn(entries);

        var response = service.findAnomalies(USER_ID, FROM, TO, 50);

        assertThat(response.from()).isEqualTo(FROM);
        assertThat(response.to()).isEqualTo(TO);
        assertThat(response.totalGroups()).isEqualTo(1);
        assertThat(response.returnedGroups()).isEqualTo(1);
        assertThat(response.content()).hasSize(1);
        var group = response.content().get(0);
        assertThat(group.type()).isEqualTo("DUPLICATE_SAME_DAY_AMOUNT_TITLE");
        assertThat(group.severity()).isEqualTo("medium");
        assertThat(group.entryCount()).isEqualTo(2);
        assertThat(group.entries()).extracting("id").containsExactly(1L, 2L);
        assertThat(group.entries()).allSatisfy(entry -> assertThat(entry.entryType()).isEqualTo(EntryType.EXPENSE));
        verify(ledgerEntryRepository).findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(USER_ID, FROM, TO);
    }

    @Test
    void findAnomaliesCapsReturnedGroupsWithoutChangingTotalGroups() {
        stubUser();
        List<LedgerEntry> entries = new ArrayList<>();
        for (int i = 0; i < 205; i += 1) {
            LocalDate date = FROM.plusDays(i % 20);
            entries.add(entry((long) i * 2 + 1, date, "merchant " + i, "1000", EntryType.EXPENSE));
            entries.add(entry((long) i * 2 + 2, date, "merchant " + i, "1000.00", EntryType.EXPENSE));
        }
        when(ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(USER_ID))
                .thenReturn(entries);

        var response = service.findAnomalies(USER_ID, null, null, 500);

        assertThat(response.from()).isNull();
        assertThat(response.to()).isNull();
        assertThat(response.totalGroups()).isEqualTo(205);
        assertThat(response.returnedGroups()).isEqualTo(200);
        assertThat(response.content()).hasSize(200);
        verify(ledgerEntryRepository).findAllByOwnerIdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(USER_ID);
    }

    @Test
    void findAnomaliesRejectsRangeLongerThan366DaysBeforeReadingEntries() {
        stubUser();
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2027, 1, 3);

        assertThatThrownBy(() -> service.findAnomalies(USER_ID, from, to, 50))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Anomaly search range cannot exceed 366 days.");

        verify(ledgerEntryRepository, never()).findAllByOwnerIdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(USER_ID);
        verify(ledgerEntryRepository, never()).findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(USER_ID, from, to);
    }

    private void stubUser() {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        user.setLoginId("owner");
        user.setDisplayName("Owner");
        user.setActive(true);
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(user);
    }

    private LedgerEntry entry(Long id, LocalDate entryDate, String title, String amount, EntryType entryType) {
        LedgerEntry entry = new LedgerEntry();
        entry.setId(id);
        entry.setEntryDate(entryDate);
        entry.setEntryTime(LocalTime.of(9, Math.toIntExact(id % 60)));
        entry.setTitle(title);
        entry.setAmount(new BigDecimal(amount));
        entry.setEntryType(entryType);
        return entry;
    }
}