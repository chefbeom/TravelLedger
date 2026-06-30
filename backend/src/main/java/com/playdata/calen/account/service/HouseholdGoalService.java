package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.HouseholdGoal;
import com.playdata.calen.account.domain.HouseholdGoalStatus;
import com.playdata.calen.account.dto.HouseholdGoalRequest;
import com.playdata.calen.account.dto.HouseholdGoalResponse;
import com.playdata.calen.account.repository.HouseholdGoalRepository;
import com.playdata.calen.account.repository.UserNotificationRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HouseholdGoalService {

    private static final String NOTIFICATION_TYPE = "GOAL_PROGRESS";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final AppUserService appUserService;
    private final HouseholdGoalRepository householdGoalRepository;
    private final UserNotificationRepository userNotificationRepository;
    private final UserNotificationService userNotificationService;

    public List<HouseholdGoalResponse> getGoals(Long userId, boolean includeArchived) {
        appUserService.getRequiredUser(userId);
        List<HouseholdGoal> goals = includeArchived
                ? householdGoalRepository.findAllByOwnerIdOrderByCreatedAtDescIdDesc(userId)
                : householdGoalRepository.findAllByOwnerIdAndStatusNotOrderByCreatedAtDescIdDesc(userId, HouseholdGoalStatus.ARCHIVED);
        return goals.stream().map(this::toResponse).toList();
    }

    @Transactional
    public HouseholdGoalResponse createGoal(Long userId, HouseholdGoalRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        HouseholdGoal goal = new HouseholdGoal();
        goal.setOwner(owner);
        apply(goal, request);
        HouseholdGoal saved = householdGoalRepository.save(goal);
        notifyGoalReachedIfNeeded(saved);
        return toResponse(saved);
    }

    @Transactional
    public HouseholdGoalResponse updateGoal(Long userId, Long goalId, HouseholdGoalRequest request) {
        appUserService.getRequiredUser(userId);
        HouseholdGoal goal = householdGoalRepository.findByIdAndOwnerId(goalId, userId)
                .orElseThrow(() -> new NotFoundException("Household goal was not found."));
        apply(goal, request);
        notifyGoalReachedIfNeeded(goal);
        return toResponse(goal);
    }

    @Transactional
    public HouseholdGoalResponse archiveGoal(Long userId, Long goalId) {
        appUserService.getRequiredUser(userId);
        HouseholdGoal goal = householdGoalRepository.findByIdAndOwnerId(goalId, userId)
                .orElseThrow(() -> new NotFoundException("Household goal was not found."));
        goal.setStatus(HouseholdGoalStatus.ARCHIVED);
        return toResponse(goal);
    }

    private void apply(HouseholdGoal goal, HouseholdGoalRequest request) {
        String title = request == null || request.title() == null ? "" : request.title().trim();
        if (title.isBlank()) {
            throw new BadRequestException("Household goal title is required.");
        }
        if (title.length() > 120) {
            throw new BadRequestException("Household goal title must be 120 characters or fewer.");
        }
        BigDecimal targetAmount = normalizeAmount(request.targetAmountKrw(), "Household goal target amount must be greater than zero.");
        if (targetAmount.compareTo(ZERO) <= 0) {
            throw new BadRequestException("Household goal target amount must be greater than zero.");
        }
        BigDecimal currentAmount = normalizeAmount(request.currentAmountKrw(), "Household goal current amount cannot be negative.");
        if (currentAmount.compareTo(ZERO) < 0) {
            throw new BadRequestException("Household goal current amount cannot be negative.");
        }

        goal.setTitle(title);
        goal.setTargetAmountKrw(targetAmount);
        goal.setCurrentAmountKrw(currentAmount);
        goal.setDueDate(request.dueDate());
        goal.setStatus(resolveStatus(request.status(), currentAmount, targetAmount));
    }

    private BigDecimal normalizeAmount(BigDecimal value, String message) {
        if (value == null) {
            throw new BadRequestException(message);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private HouseholdGoalStatus resolveStatus(HouseholdGoalStatus requestedStatus, BigDecimal currentAmount, BigDecimal targetAmount) {
        if (requestedStatus == HouseholdGoalStatus.ARCHIVED) {
            return HouseholdGoalStatus.ARCHIVED;
        }
        if (currentAmount.compareTo(targetAmount) >= 0) {
            return HouseholdGoalStatus.ACHIEVED;
        }
        return HouseholdGoalStatus.ACTIVE;
    }

    private void notifyGoalReachedIfNeeded(HouseholdGoal goal) {
        if (goal.getId() == null || goal.getOwner() == null || goal.getOwner().getId() == null) {
            return;
        }
        if (goal.getStatus() != HouseholdGoalStatus.ACHIEVED) {
            return;
        }
        Long ownerId = goal.getOwner().getId();
        String targetUrl = "/household?tab=goals&goalId=" + goal.getId();
        if (userNotificationRepository.existsByOwnerIdAndTypeAndTargetUrlAndReadAtIsNull(ownerId, NOTIFICATION_TYPE, targetUrl)) {
            return;
        }
        try {
            userNotificationService.createSystemNotification(
                    ownerId,
                    NOTIFICATION_TYPE,
                    "Household goal reached",
                    "A household goal reached its target. Review the goal before making any ledger or settlement changes.",
                    targetUrl,
                    goalProgressMetadata(goal.getId())
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to create household goal notification: goalId={}, ownerId={}", goal.getId(), ownerId, exception);
        }
    }

    private String goalProgressMetadata(Long goalId) {
        return "{\"goalId\":" + goalId + ",\"status\":\"achieved\",\"progressBucket\":\"100_plus\",\"visibility\":\"owner\"}";
    }

    private HouseholdGoalResponse toResponse(HouseholdGoal goal) {
        return new HouseholdGoalResponse(
                goal.getId(),
                goal.getTitle(),
                goal.getTargetAmountKrw(),
                goal.getCurrentAmountKrw(),
                progressBucket(goal.getCurrentAmountKrw(), goal.getTargetAmountKrw()),
                goal.getDueDate(),
                goal.getStatus(),
                goal.getVersion(),
                goal.getCreatedAt(),
                goal.getUpdatedAt()
        );
    }

    private String progressBucket(BigDecimal currentAmount, BigDecimal targetAmount) {
        if (targetAmount == null || targetAmount.compareTo(ZERO) <= 0) {
            return "not_started";
        }
        BigDecimal current = currentAmount == null ? ZERO : currentAmount;
        int percent = current.multiply(BigDecimal.valueOf(100)).divide(targetAmount, 0, RoundingMode.DOWN).intValue();
        if (percent >= 100) {
            return "100_plus";
        }
        if (percent >= 75) {
            return "75_99";
        }
        if (percent >= 50) {
            return "50_74";
        }
        if (percent > 0) {
            return "1_49";
        }
        return "not_started";
    }
}