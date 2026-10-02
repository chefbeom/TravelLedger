package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.jobs.WorkLeaseService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

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
        var service = mock(TravelService.class);
        ObjectProvider<TravelService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(service);
        var coordinator = new TravelClusterRefreshCoordinator(dataSource, manager, leases, provider);
        AtomicBoolean first = new AtomicBoolean(true);
        when(service.computeFreshPhotoClusters(7L)).thenAnswer(call -> {
            if (first.compareAndSet(true, false)) coordinator.markDirty(7L);
            return List.of();
        });
        try {
            coordinator.markDirty(7L);
            assertThat(coordinator.ensureCurrent(7L)).isEqualTo(2L);
            verify(service, times(2)).computeFreshPhotoClusters(7L);
            verify(service, times(1)).applyFreshPhotoClusters(7L, List.of());
            assertThat(jdbc.queryForObject("select dirty from travel_photo_cluster_refresh_jobs where owner_id = 7", Boolean.class)).isFalse();
            new TransactionTemplate(manager).executeWithoutResult(transaction -> {
                coordinator.markDirtyInCurrentTransaction(8L);
                transaction.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject("select count(*) from travel_photo_cluster_refresh_jobs where owner_id = 8", Integer.class)).isZero();
        } finally { coordinator.shutdown(); }
    }
}
