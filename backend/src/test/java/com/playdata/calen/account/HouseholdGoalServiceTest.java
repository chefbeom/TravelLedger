package com.playdata.calen.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.HouseholdGoal;
import com.playdata.calen.account.domain.HouseholdGoalStatus;
import com.playdata.calen.account.dto.HouseholdGoalRequest;
import com.playdata.calen.account.repository.HouseholdGoalRepository;
import com.playdata.calen.account.repository.UserNotificationRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.HouseholdGoalService;
import com.playdata.calen.account.service.UserNotificationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HouseholdGoalServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private AppUserService appUserService;

    @Mock
    private HouseholdGoalRepository householdGoalRepository;

    @Mock
    private UserNotificationRepository userNotificationRepository;

    @Mock
    private UserNotificationService userNotificationService;

    private HouseholdGoalService service;

    @BeforeEach
    void setUp() {
        service = new HouseholdGoalService(
                appUserService,
                householdGoalRepository,
                userNotificationRepository,
                userNotificationService
        );
    }

    @Test
    void createGoalNotifiesOwnerWhenProgressReachesTargetWithBoundedMetadata() {
        AppUser owner = owner();
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(owner);
        when(householdGoalRepository.save(any(HouseholdGoal.class))).thenAnswer(invocation -> {
            HouseholdGoal goal = invocation.getArgument(0);
            goal.setId(99L);
            return goal;
        });
        when(userNotificationRepository.existsByOwnerIdAndTypeAndTargetUrlAndReadAtIsNull(
                USER_ID,
                "GOAL_PROGRESS",
                "/household?tab=goals&goalId=99"
        )).thenReturn(false);

        service.createGoal(USER_ID, new HouseholdGoalRequest(
                "Emergency fund",
                new BigDecimal("100000.00"),
                new BigDecimal("100000.00"),
                LocalDate.of(2026, 12, 31),
                null
        ));

        verify(userNotificationService).createSystemNotification(
                eq(USER_ID),
                eq("GOAL_PROGRESS"),
                eq("Household goal reached"),
                eq("A household goal reached its target. Review the goal before making any ledger or settlement changes."),
                eq("/household?tab=goals&goalId=99"),
                eq("{\"goalId\":99,\"status\":\"achieved\",\"progressBucket\":\"100_plus\",\"visibility\":\"owner\"}")
        );
    }

    @Test
    void archiveGoalUsesOwnerScopedLookupWithoutGoalProgressNotification() {
        HouseholdGoal goal = new HouseholdGoal();
        goal.setId(99L);
        goal.setOwner(owner());
        goal.setTitle("Emergency fund");
        goal.setTargetAmountKrw(new BigDecimal("100000.00"));
        goal.setCurrentAmountKrw(new BigDecimal("100000.00"));
        goal.setStatus(HouseholdGoalStatus.ACHIEVED);
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(owner());
        when(householdGoalRepository.findByIdAndOwnerId(99L, USER_ID)).thenReturn(Optional.of(goal));

        service.archiveGoal(USER_ID, 99L);

        verify(householdGoalRepository).findByIdAndOwnerId(99L, USER_ID);
        verify(userNotificationService, never()).createSystemNotification(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    private AppUser owner() {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        user.setLoginId("owner@example.com");
        user.setDisplayName("Owner");
        user.setPasswordHash("{noop}password");
        user.setActive(true);
        return user;
    }
}