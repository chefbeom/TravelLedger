package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.PhotoPoint;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TravelClusterRefreshCoordinatorTest {
    @Test
    @SuppressWarnings("unchecked")
    void changedGenerationDiscardsOldCalculationAndOnlySavesLatestResult() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table app_work_leases (lease_key varchar(240) primary key, owner_token varchar(36), expires_at_epoch_millis bigint)");
        jdbc.execute("create table travel_photo_cluster_refresh_jobs (owner_id bigint primary key, generation bigint, processed_generation bigint, dirty boolean)");
        var manager = new DataSourceTransactionManager(dataSource);
        var leases = new WorkLeaseService(dataSource, manager);
        var service = mock(RefreshTravelPhotoClustersUseCase.class);
        var coordinator = new TravelClusterRefreshCoordinator(dataSource, manager, leases, service);
        AtomicBoolean first = new AtomicBoolean(true);
        var oldResult = new TravelPhotoClusterPolicy().cluster(List.of(point(1L)));
        var currentResult = new TravelPhotoClusterPolicy().cluster(List.of(point(2L)));
        when(service.compute(7L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (first.compareAndSet(true, false)) {
                coordinator.markDirty(7L);
                return oldResult;
            }
            return currentResult;
        });
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        }).when(service).apply(eq(7L), anyList());
        try {
            coordinator.markDirty(7L);
            assertThat(coordinator.ensureCurrent(7L)).isEqualTo(2L);
            verify(service, times(2)).compute(7L);
            verify(service, times(1)).apply(7L, currentResult);
            verify(service, never()).apply(7L, oldResult);
            assertThat(jdbc.queryForObject("select dirty from travel_photo_cluster_refresh_jobs where owner_id = 7", Boolean.class)).isFalse();
            new TransactionTemplate(manager).executeWithoutResult(transaction -> {
                coordinator.markDirtyInCurrentTransaction(8L);
                transaction.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject("select count(*) from travel_photo_cluster_refresh_jobs where owner_id = 8", Integer.class)).isZero();
        } finally { coordinator.shutdown(); }
    }

    private PhotoPoint point(Long id) {
        return new PhotoPoint(id, id, 3L, "Trip", "#3182F6", LocalDate.of(2026,10,15), LocalTime.NOON,
                "Spot", "Place", null, null, null, new BigDecimal("37.5"), new BigDecimal("127"), LocalDateTime.of(2026,10,15,12,0), false);
    }
}
