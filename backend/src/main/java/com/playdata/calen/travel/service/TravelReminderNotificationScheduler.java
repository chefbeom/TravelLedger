package com.playdata.calen.travel.service;

import com.playdata.calen.account.service.UserNotificationService;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TravelReminderNotificationScheduler {

    private final TravelPlanRepository travelPlanRepository;
    private final UserNotificationService userNotificationService;

    @Value("${app.notifications.travel-reminders-enabled:true}")
    private boolean travelRemindersEnabled;

    @Value("${app.notifications.travel-reminder-zone:Asia/Seoul}")
    private String travelReminderZone;

    @PostConstruct
    void logSchedulerConfiguration() {
        log.info(
                "Travel reminder notifications configured: enabled={}, zone='{}'",
                travelRemindersEnabled,
                travelReminderZone
        );
    }

    @Scheduled(cron = "${app.notifications.travel-reminder-cron:0 0 8 * * *}", zone = "${app.notifications.travel-reminder-zone:Asia/Seoul}")
    public void runDailyTravelReminders() {
        if (!travelRemindersEnabled) {
            return;
        }
        runTravelRemindersForDate(todayInConfiguredZone());
    }

    void runTravelRemindersForDate(LocalDate today) {
        LocalDate targetDate = today.plusDays(1);
        List<TravelPlan> plans = travelPlanRepository.findAllByStatusAndStartDateBetweenOrderByStartDateAscIdAsc(
                TravelPlanStatus.PLANNED,
                targetDate,
                targetDate
        );
        for (TravelPlan plan : plans) {
            notifyTravelStartsTomorrow(plan);
        }
    }

    private LocalDate todayInConfiguredZone() {
        try {
            return LocalDate.now(ZoneId.of(travelReminderZone));
        } catch (RuntimeException exception) {
            log.warn("Invalid travel reminder zone '{}'; using system default date.", travelReminderZone, exception);
            return LocalDate.now();
        }
    }

    private void notifyTravelStartsTomorrow(TravelPlan plan) {
        Long planId = plan.getId();
        Long ownerId = plan.getOwner() != null ? plan.getOwner().getId() : null;
        LocalDate startDate = plan.getStartDate();
        if (planId == null || ownerId == null || startDate == null) {
            return;
        }
        try {
            userNotificationService.createSystemNotification(
                    ownerId,
                    "TRAVEL_REMINDER",
                    "Travel starts tomorrow",
                    "A planned trip starts tomorrow. Review itinerary, budget, and upload readiness before departure.",
                    "/travel-money?planId=" + planId,
                    travelReminderMetadata(planId, startDate)
            );
        } catch (RuntimeException notificationException) {
            log.warn("Failed to create travel reminder notification: planId={}, ownerId={}", planId, ownerId, notificationException);
        }
    }

    private String travelReminderMetadata(Long planId, LocalDate startDate) {
        return "{\"planId\":" + planId + ",\"dateLabel\":\"" + startDate + "\",\"status\":\"starting_tomorrow\"}";
    }
}