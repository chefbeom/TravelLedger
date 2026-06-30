package com.playdata.calen.travel.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TravelBudgetWarningNotificationScheduler {

    private static final String NOTIFICATION_TYPE = "BUDGET_WARNING";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final TravelPlanRepository travelPlanRepository;
    private final TravelBudgetItemRepository travelBudgetItemRepository;
    private final TravelExpenseRecordRepository travelExpenseRecordRepository;
    private final UserNotificationRepository userNotificationRepository;
    private final UserNotificationService userNotificationService;

    @Value("${app.notifications.travel-budget-warnings-enabled:true}")
    private boolean travelBudgetWarningsEnabled;

    @Scheduled(cron = "${app.notifications.travel-budget-warning-cron:0 20 8 * * *}", zone = "${app.notifications.travel-budget-warning-zone:Asia/Seoul}")
    public void runDailyBudgetWarnings() {
        if (!travelBudgetWarningsEnabled) {
            return;
        }
        runBudgetWarnings();
    }

    void runBudgetWarnings() {
        List<TravelPlan> plans = travelPlanRepository.findAllByStatusInOrderByStartDateDescIdDesc(List.of(
                TravelPlanStatus.PLANNED,
                TravelPlanStatus.COMPLETED
        ));
        for (TravelPlan plan : plans) {
            notifyIfBudgetExceeded(plan);
        }
    }

    private void notifyIfBudgetExceeded(TravelPlan plan) {
        Long planId = plan.getId();
        Long ownerId = plan.getOwner() != null ? plan.getOwner().getId() : null;
        if (planId == null || ownerId == null) {
            return;
        }

        BigDecimal plannedTotalKrw = zeroIfNull(travelBudgetItemRepository.sumAmountKrwByPlanId(planId));
        if (plannedTotalKrw.compareTo(ZERO) <= 0) {
            return;
        }

        BigDecimal spentTotalKrw = zeroIfNull(travelExpenseRecordRepository.sumAmountKrwByPlanIdAndRecordType(
                planId,
                TravelRecordType.LEDGER
        ));
        if (spentTotalKrw.compareTo(plannedTotalKrw) <= 0) {
            return;
        }

        String targetUrl = "/travel-money?planId=" + planId + "&tab=records";
        if (userNotificationRepository.existsByOwnerIdAndTypeAndTargetUrlAndReadAtIsNull(ownerId, NOTIFICATION_TYPE, targetUrl)) {
            return;
        }

        try {
            userNotificationService.createSystemNotification(
                    ownerId,
                    NOTIFICATION_TYPE,
                    "Travel budget exceeded",
                    "Travel ledger spending is over the planned budget. Review the trip ledger before adding more shared expenses.",
                    targetUrl,
                    budgetWarningMetadata(planId)
            );
        } catch (RuntimeException notificationException) {
            log.warn("Failed to create travel budget warning notification: planId={}, ownerId={}", planId, ownerId, notificationException);
        }
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : ZERO;
    }

    private String budgetWarningMetadata(Long planId) {
        return "{\"planId\":" + planId + ",\"thresholdLabel\":\"over_100_percent\",\"period\":\"trip\",\"status\":\"exceeded\"}";
    }
}