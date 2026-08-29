package com.playdata.calen.ledger.service;

import com.playdata.calen.ledger.domain.RecurringLedgerScheduleType;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

final class RecurringLedgerSchedule {

    static final int MAX_INTERVAL_DAYS = 3650;
    static final int MAX_MONTH_INTERVAL = 120;

    private RecurringLedgerSchedule() {
    }

    static LocalDate scheduledDate(int dayOfMonth, YearMonth month) {
        return month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
    }

    static boolean isDue(int dayOfMonth, LocalDate startDate, LocalDate endDate, LocalDate date) {
        return isDue(
                RecurringLedgerScheduleType.MONTHLY_DATE,
                dayOfMonth,
                1,
                null,
                startDate,
                endDate,
                date
        );
    }

    static boolean isDue(
            RecurringLedgerScheduleType scheduleType,
            Integer dayOfMonth,
            Integer monthInterval,
            Integer intervalDays,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate date
    ) {
        if (!isWithinRange(startDate, endDate, date)) {
            return false;
        }

        if (resolve(scheduleType) == RecurringLedgerScheduleType.EVERY_N_DAYS) {
            if (!isValidInterval(intervalDays) || startDate == null) {
                return false;
            }
            return ChronoUnit.DAYS.between(startDate, date) % intervalDays == 0;
        }

        if (dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 31
                || !isValidMonthInterval(monthInterval) || startDate == null) {
            return false;
        }
        YearMonth startMonth = YearMonth.from(startDate);
        YearMonth dateMonth = YearMonth.from(date);
        long monthOffset = ChronoUnit.MONTHS.between(startMonth, dateMonth);
        return monthOffset >= 0
                && monthOffset % monthInterval == 0
                && scheduledDate(dayOfMonth, dateMonth).equals(date);
    }

    static LocalDate nextDueDate(int dayOfMonth, LocalDate startDate, LocalDate endDate, LocalDate from) {
        return nextDueDate(
                RecurringLedgerScheduleType.MONTHLY_DATE,
                dayOfMonth,
                1,
                null,
                startDate,
                endDate,
                from
        );
    }

    static LocalDate nextDueDate(
            RecurringLedgerScheduleType scheduleType,
            Integer dayOfMonth,
            Integer monthInterval,
            Integer intervalDays,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate from
    ) {
        if (startDate == null || from == null || (endDate != null && endDate.isBefore(from))) {
            return null;
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            return null;
        }

        if (resolve(scheduleType) == RecurringLedgerScheduleType.EVERY_N_DAYS) {
            return nextIntervalDueDate(intervalDays, startDate, endDate, from);
        }

        if (dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 31
                || !isValidMonthInterval(monthInterval)) {
            return null;
        }

        int effectiveMonthInterval = monthInterval;
        YearMonth startMonth = YearMonth.from(startDate);
        YearMonth requestedMonth = YearMonth.from(from);
        long elapsedMonths = Math.max(0, ChronoUnit.MONTHS.between(startMonth, requestedMonth));
        long periods = elapsedMonths / effectiveMonthInterval;
        if (elapsedMonths % effectiveMonthInterval != 0) {
            periods++;
        }

        YearMonth month;
        try {
            month = startMonth.plusMonths(Math.multiplyExact(periods, (long) effectiveMonthInterval));
        } catch (DateTimeException | ArithmeticException exception) {
            return null;
        }

        for (int offset = 0; offset <= 1200; offset++) {
            LocalDate candidate = scheduledDate(dayOfMonth, month);
            if (!candidate.isBefore(from)
                    && !candidate.isBefore(startDate)
                    && (endDate == null || !candidate.isAfter(endDate))) {
                return candidate;
            }
            try {
                month = month.plusMonths(effectiveMonthInterval);
            } catch (DateTimeException exception) {
                return null;
            }
        }
        return null;
    }

    private static LocalDate nextIntervalDueDate(
            Integer intervalDays,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate from
    ) {
        if (!isValidInterval(intervalDays)) {
            return null;
        }

        LocalDate candidate;
        if (from.isBefore(startDate)) {
            candidate = startDate;
        } else {
            long elapsedDays = ChronoUnit.DAYS.between(startDate, from);
            long periods = elapsedDays / intervalDays;
            if (elapsedDays % intervalDays != 0) {
                periods++;
            }
            try {
                candidate = startDate.plusDays(Math.multiplyExact(periods, intervalDays.longValue()));
            } catch (DateTimeException | ArithmeticException exception) {
                return null;
            }
        }

        return endDate == null || !candidate.isAfter(endDate) ? candidate : null;
    }

    private static boolean isWithinRange(LocalDate startDate, LocalDate endDate, LocalDate date) {
        return date != null
                && (startDate == null || !date.isBefore(startDate))
                && (endDate == null || !date.isAfter(endDate));
    }

    private static boolean isValidInterval(Integer intervalDays) {
        return intervalDays != null && intervalDays >= 1 && intervalDays <= MAX_INTERVAL_DAYS;
    }

    private static boolean isValidMonthInterval(Integer monthInterval) {
        return monthInterval != null && monthInterval >= 1 && monthInterval <= MAX_MONTH_INTERVAL;
    }

    private static RecurringLedgerScheduleType resolve(RecurringLedgerScheduleType scheduleType) {
        return scheduleType == null ? RecurringLedgerScheduleType.MONTHLY_DATE : scheduleType;
    }
}
