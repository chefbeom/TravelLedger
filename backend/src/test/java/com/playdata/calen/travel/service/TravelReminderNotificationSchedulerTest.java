package com.playdata.calen.travel.service;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.UserNotificationService;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelReminderNotificationSchedulerTest {

    @Test
    void notifiesOwnersWhenPlannedTravelStartsTomorrowWithBoundedMetadata() {
        TravelPlanRepository travelPlanRepository = mock(TravelPlanRepository.class);
        UserNotificationService userNotificationService = mock(UserNotificationService.class);
        TravelReminderNotificationScheduler scheduler = new TravelReminderNotificationScheduler(
                travelPlanRepository,
                userNotificationService
        );
        LocalDate today = LocalDate.of(2026, 6, 30);
        LocalDate tomorrow = LocalDate.of(2026, 7, 1);
        when(travelPlanRepository.findAllByStatusAndStartDateBetweenOrderByStartDateAscIdAsc(
                TravelPlanStatus.PLANNED,
                tomorrow,
                tomorrow
        )).thenReturn(List.of(plan(7L, 42L, tomorrow)));

        scheduler.runTravelRemindersForDate(today);

        verify(userNotificationService).createSystemNotification(
                eq(42L),
                eq("TRAVEL_REMINDER"),
                eq("Travel starts tomorrow"),
                contains("planned trip starts tomorrow"),
                eq("/travel-money?planId=7"),
                eq("{\"planId\":7,\"dateLabel\":\"2026-07-01\",\"status\":\"starting_tomorrow\"}")
        );
    }

    private TravelPlan plan(Long planId, Long ownerId, LocalDate startDate) {
        AppUser owner = new AppUser();
        owner.setId(ownerId);
        TravelPlan plan = new TravelPlan();
        plan.setId(planId);
        plan.setOwner(owner);
        plan.setStartDate(startDate);
        plan.setEndDate(startDate.plusDays(3));
        plan.setStatus(TravelPlanStatus.PLANNED);
        plan.setName("Private trip name should not be copied into notification metadata");
        plan.setDestination("Private destination should not be copied into notification metadata");
        plan.setMemo("Private notes should not be copied into notification metadata");
        return plan;
    }
}