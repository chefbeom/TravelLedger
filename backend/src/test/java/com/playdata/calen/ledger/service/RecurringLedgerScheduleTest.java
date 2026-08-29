package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playdata.calen.ledger.domain.RecurringLedgerScheduleType;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class RecurringLedgerScheduleTest {

    @Test
    void dayThirtyOneFallsBackToTheLastDayOfShortMonth() {
        assertThat(RecurringLedgerSchedule.scheduledDate(31, YearMonth.of(2026, 2)))
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(RecurringLedgerSchedule.isDue(
                31,
                LocalDate.of(2026, 1, 1),
                null,
                LocalDate.of(2026, 2, 28)
        )).isTrue();
    }

    @Test
    void nextDueDateRespectsStartAndEndDates() {
        assertThat(RecurringLedgerSchedule.nextDueDate(
                15,
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 8, 1)
        )).isEqualTo(LocalDate.of(2026, 9, 15));

        assertThat(RecurringLedgerSchedule.nextDueDate(
                15,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 8, 15)
        )).isNull();
    }

    @Test
    void monthIntervalUsesTheStartMonthAsTheStableAnchor() {
        LocalDate startDate = LocalDate.of(2026, 8, 1);

        assertThat(RecurringLedgerSchedule.isDue(
                RecurringLedgerScheduleType.MONTHLY_DATE,
                15,
                2,
                null,
                startDate,
                null,
                LocalDate.of(2026, 8, 15)
        )).isTrue();
        assertThat(RecurringLedgerSchedule.isDue(
                RecurringLedgerScheduleType.MONTHLY_DATE,
                15,
                2,
                null,
                startDate,
                null,
                LocalDate.of(2026, 9, 15)
        )).isFalse();
        assertThat(RecurringLedgerSchedule.nextDueDate(
                RecurringLedgerScheduleType.MONTHLY_DATE,
                15,
                2,
                null,
                startDate,
                null,
                LocalDate.of(2026, 9, 1)
        )).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void monthIntervalSupportsQuarterAndEighteenMonthPeriods() {
        LocalDate startDate = LocalDate.of(2026, 1, 1);

        assertThat(RecurringLedgerSchedule.isDue(
                RecurringLedgerScheduleType.MONTHLY_DATE,
                31,
                3,
                null,
                startDate,
                null,
                LocalDate.of(2026, 4, 30)
        )).isTrue();
        assertThat(RecurringLedgerSchedule.nextDueDate(
                RecurringLedgerScheduleType.MONTHLY_DATE,
                31,
                18,
                null,
                startDate,
                null,
                LocalDate.of(2026, 2, 1)
        )).isEqualTo(LocalDate.of(2027, 7, 31));
    }

    @Test
    void everyNDaysUsesTheStartDateAsTheFirstOccurrence() {
        LocalDate startDate = LocalDate.of(2026, 8, 1);

        assertThat(RecurringLedgerSchedule.isDue(
                RecurringLedgerScheduleType.EVERY_N_DAYS,
                null,
                null,
                23,
                startDate,
                null,
                LocalDate.of(2026, 8, 24)
        )).isTrue();
        assertThat(RecurringLedgerSchedule.isDue(
                RecurringLedgerScheduleType.EVERY_N_DAYS,
                null,
                null,
                23,
                startDate,
                null,
                LocalDate.of(2026, 8, 25)
        )).isFalse();
        assertThat(RecurringLedgerSchedule.nextDueDate(
                RecurringLedgerScheduleType.EVERY_N_DAYS,
                null,
                null,
                45,
                startDate,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 8, 2)
        )).isEqualTo(LocalDate.of(2026, 9, 15));
    }
}
