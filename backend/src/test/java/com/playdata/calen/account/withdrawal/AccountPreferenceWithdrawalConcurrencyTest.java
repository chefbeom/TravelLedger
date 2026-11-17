package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.dto.HouseholdAggregatePreferencesRequest;
import com.playdata.calen.account.dto.HouseholdGoalRequest;
import com.playdata.calen.account.dto.UserLayoutSettingRequest;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.HouseholdGoalRepository;
import com.playdata.calen.account.repository.UserLayoutSettingRepository;
import com.playdata.calen.account.service.HouseholdAggregatePreferenceService;
import com.playdata.calen.account.service.HouseholdGoalService;
import com.playdata.calen.account.service.UserLayoutSettingService;
import com.playdata.calen.common.exception.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "app.seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:preference-withdrawal-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class AccountPreferenceWithdrawalConcurrencyTest {
    @Autowired AppUserRepository users;
    @Autowired UserLayoutSettingRepository layouts;
    @Autowired HouseholdGoalRepository goals;
    @Autowired UserLayoutSettingService layoutService;
    @Autowired HouseholdAggregatePreferenceService aggregateService;
    @Autowired HouseholdGoalService goalService;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;

    @Test void preferenceAndGoalWritesWaitForDeactivationAndCannotRecreateData() throws Exception {
        AppUser owner = new AppUser();
        owner.setLoginId("preference_" + UUID.randomUUID());
        owner.setDisplayName("테스트 회원");
        owner = users.saveAndFlush(owner);
        Long ownerId = owner.getId();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(3);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                AppUser current = users.lockForAccountSecurityUpdate(ownerId).orElseThrow();
                current.setActive(false);
                users.saveAndFlush(current);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("deactivation timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var layout = pool.submit(() -> {
                started.countDown();
                return layoutService.saveLayoutSetting(ownerId, "calendar", new UserLayoutSettingRequest(1, mapper.createObjectNode()));
            });
            var aggregate = pool.submit(() -> {
                started.countDown();
                return aggregateService.savePreferences(ownerId, new HouseholdAggregatePreferencesRequest(List.of()));
            });
            var goal = pool.submit(() -> {
                started.countDown();
                return goalService.createGoal(ownerId, new HouseholdGoalRequest("목표", BigDecimal.TEN, BigDecimal.ZERO, null, null));
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            for (var future : List.of(layout, aggregate, goal)) {
                assertThatThrownBy(() -> future.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            }
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            for (var future : List.of(layout, aggregate, goal)) {
                assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(NotFoundException.class);
            }
            assertThat(layouts.findByOwnerIdAndLayoutScope(ownerId, "calendar")).isEmpty();
            assertThat(goals.findAllByOwnerIdOrderByCreatedAtDescIdDesc(ownerId)).isEmpty();
            assertThat(users.findById(ownerId).orElseThrow().getHouseholdAggregateSettingsJson()).isNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
