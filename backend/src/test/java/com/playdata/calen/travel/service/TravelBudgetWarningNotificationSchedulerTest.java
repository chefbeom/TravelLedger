package com.playdata.calen.travel.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.UserNotificationRepository;
import com.playdata.calen.account.service.UserNotificationService;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.repository.TravelBudgetItemRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelBudgetWarningNotificationSchedulerTest {

    @Test
    void notifiesOwnerWhenTravelSpendingExceedsBudgetWithBoundedMetadata() {
        TravelPlanRepository travelPlanRepository = mock(TravelPlanRepository.class);
        TravelBudgetItemRepository travelBudgetItemRepository = mock(TravelBudgetItemRepository.class);
        TravelExpenseRecordRepository travelExpenseRecordRepository = mock(TravelExpenseRecordRepository.class);
        UserNotificationRepository userNotificationRepository = mock(UserNotificationRepository.class);
        UserNotificationService userNotificationService = mock(UserNotificationService.class);
        TravelBudgetWarningNotificationScheduler scheduler = new TravelBudgetWarningNotificationScheduler(
                travelPlanRepository,
                travelBudgetItemRepository,
                travelExpenseRecordRepository,
                userNotificationRepository,
                userNotificationService
        );
        TravelPlan plan = plan(7L, 42L);
        when(travelPlanRepository.findAllByStatusInOrderByStartDateDescIdDesc(List.of(
                TravelPlanStatus.PLANNED,
                TravelPlanStatus.COMPLETED
        ))).thenReturn(List.of(plan));
        when(travelBudgetItemRepository.sumAmountKrwByPlanId(7L)).thenReturn(new BigDecimal("100000.00"));
        when(travelExpenseRecordRepository.sumAmountKrwByPlanIdAndRecordType(7L, TravelRecordType.LEDGER))
                .thenReturn(new BigDecimal("125000.00"));
        when(userNotificationRepository.existsByOwnerIdAndTypeAndTargetUrlAndReadAtIsNull(
                42L,
                "BUDGET_WARNING",
                "/travel-money?planId=7&tab=records"
        )).thenReturn(false);

        scheduler.runBudgetWarnings();

        verify(userNotificationService).createSystemNotification(
                eq(42L),
                eq("BUDGET_WARNING"),
                eq("Travel budget exceeded"),
                eq("Travel ledger spending is over the planned budget. Review the trip ledger before adding more shared expenses."),
                eq("/travel-money?planId=7&tab=records"),
                eq("{\"planId\":7,\"thresholdLabel\":\"over_100_percent\",\"period\":\"trip\",\"status\":\"exceeded\"}")
        );
    }

    @Test
    void skipsDuplicateUnreadTravelBudgetWarningForSamePlan() {
        TravelPlanRepository travelPlanRepository = mock(TravelPlanRepository.class);
        TravelBudgetItemRepository travelBudgetItemRepository = mock(TravelBudgetItemRepository.class);
        TravelExpenseRecordRepository travelExpenseRecordRepository = mock(TravelExpenseRecordRepository.class);
        UserNotificationRepository userNotificationRepository = mock(UserNotificationRepository.class);
        UserNotificationService userNotificationService = mock(UserNotificationService.class);
        TravelBudgetWarningNotificationScheduler scheduler = new TravelBudgetWarningNotificationScheduler(
                travelPlanRepository,
                travelBudgetItemRepository,
                travelExpenseRecordRepository,
                userNotificationRepository,
                userNotificationService
        );
        TravelPlan plan = plan(7L, 42L);
        when(travelPlanRepository.findAllByStatusInOrderByStartDateDescIdDesc(List.of(
                TravelPlanStatus.PLANNED,
                TravelPlanStatus.COMPLETED
        ))).thenReturn(List.of(plan));
        when(travelBudgetItemRepository.sumAmountKrwByPlanId(7L)).thenReturn(new BigDecimal("100000.00"));
        when(travelExpenseRecordRepository.sumAmountKrwByPlanIdAndRecordType(7L, TravelRecordType.LEDGER))
                .thenReturn(new BigDecimal("125000.00"));
        when(userNotificationRepository.existsByOwnerIdAndTypeAndTargetUrlAndReadAtIsNull(
                42L,
                "BUDGET_WARNING",
                "/travel-money?planId=7&tab=records"
        )).thenReturn(true);

        scheduler.runBudgetWarnings();

        verify(userNotificationService, never()).createSystemNotification(
                eq(42L),
                eq("BUDGET_WARNING"),
                eq("Travel budget exceeded"),
                eq("Travel ledger spending is over the planned budget. Review the trip ledger before adding more shared expenses."),
                eq("/travel-money?planId=7&tab=records"),
                eq("{\"planId\":7,\"thresholdLabel\":\"over_100_percent\",\"period\":\"trip\",\"status\":\"exceeded\"}")
        );
    }

    private TravelPlan plan(Long planId, Long ownerId) {
        AppUser owner = new AppUser();
        owner.setId(ownerId);
        TravelPlan plan = new TravelPlan();
        plan.setId(planId);
        plan.setOwner(owner);
        plan.setStatus(TravelPlanStatus.PLANNED);
        plan.setName("Private trip name should not be copied into budget notification metadata");
        plan.setDestination("Private destination should not be copied into budget notification metadata");
        plan.setMemo("Private notes should not be copied into budget notification metadata");
        return plan;
    }
}