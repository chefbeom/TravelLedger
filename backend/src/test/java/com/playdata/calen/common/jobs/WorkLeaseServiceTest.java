package com.playdata.calen.common.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WorkLeaseServiceTest {
    private final DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private final WorkLeaseService first = new WorkLeaseService(dataSource, new DataSourceTransactionManager(dataSource));
    private final WorkLeaseService second = new WorkLeaseService(dataSource, new DataSourceTransactionManager(dataSource));

    WorkLeaseServiceTest() {
        jdbc.execute("create table app_work_leases (lease_key varchar(240) primary key, owner_token varchar(36) not null, expires_at_epoch_millis bigint not null)");
    }

    @Test
    void twoInstancesShareOwnershipAndOldOwnerCannotReleaseNewLease() {
        var old = first.tryAcquire("job", Duration.ofMinutes(2));
        assertThat(old).isNotNull();
        assertThat(second.tryAcquire("job", Duration.ofMinutes(2))).isNull();
        jdbc.update("update app_work_leases set expires_at_epoch_millis = 0 where lease_key = 'job'");
        var next = second.tryAcquire("job", Duration.ofMinutes(2));
        assertThat(next).isNotNull();
        first.renewActiveLeases();
        assertThat(old.isValid()).isFalse();
        old.close();
        assertThat(first.tryAcquire("job", Duration.ofMinutes(2))).isNull();
        next.close();
        var again = first.tryAcquire("job", Duration.ofMinutes(2));
        assertThat(again).isNotNull();
        again.close();
    }

    @Test
    void simultaneousClaimsHaveOnlyOneWinner() {
        var calls = IntStream.range(0, 16).mapToObj(index -> CompletableFuture.supplyAsync(() ->
                (index % 2 == 0 ? first : second).tryAcquire("provider", Duration.ofMinutes(30)))).toList();
        var winners = calls.stream().map(CompletableFuture::join).filter(java.util.Objects::nonNull).toList();
        assertThat(winners).hasSize(1);
        winners.get(0).close();
    }

    @Test
    void rateIntervalRemainsReservedAfterReturning() {
        assertThat(first.tryReserveInterval("geocode", Duration.ofSeconds(2))).isTrue();
        assertThat(second.tryReserveInterval("geocode", Duration.ofSeconds(2))).isFalse();
        jdbc.update("update app_work_leases set expires_at_epoch_millis = 0");
        assertThat(second.tryReserveInterval("geocode", Duration.ofSeconds(2))).isTrue();
    }
}
