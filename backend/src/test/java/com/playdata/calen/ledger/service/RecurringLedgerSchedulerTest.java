package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class RecurringLedgerSchedulerTest {

    @Test
    void skipsProcessingWhenDisabled() throws Exception {
        RecurringLedgerService service = mock(RecurringLedgerService.class);
        RecurringLedgerScheduler scheduler = new RecurringLedgerScheduler(service);
        setBooleanField(scheduler, "enabled", false);

        scheduler.processDueRecurringLedgers();

        verify(service, never()).processDueDate(any());
    }

    @Test
    void processesTodayWhenEnabled() throws Exception {
        RecurringLedgerService service = mock(RecurringLedgerService.class);
        when(service.processDueDate(any(LocalDate.class))).thenReturn(1);
        RecurringLedgerScheduler scheduler = new RecurringLedgerScheduler(service);
        setBooleanField(scheduler, "enabled", true);

        scheduler.processDueRecurringLedgers();

        verify(service, times(1)).processDueDate(any(LocalDate.class));
    }

    @Test
    void defaultsToFiveMinutesAfterMidnightInKst() throws Exception {
        Method method = RecurringLedgerScheduler.class.getDeclaredMethod("processDueRecurringLedgers");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${app.ledger.recurring.cron:0 5 0 * * *}");
        assertThat(scheduled.zone()).isEqualTo("${app.ledger.recurring.zone:Asia/Seoul}");

        RecurringLedgerScheduler scheduler = new RecurringLedgerScheduler(mock(RecurringLedgerService.class));
        assertThat(getField(scheduler, "zone")).isEqualTo("Asia/Seoul");
    }

    private void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
