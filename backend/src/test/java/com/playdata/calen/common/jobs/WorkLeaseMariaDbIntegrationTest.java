package com.playdata.calen.common.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.playdata.calen.ledger.ocr.LedgerImageConcurrencyService;
import com.playdata.calen.ledger.ocr.LedgerOcrAsyncConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in: a disposable, dedicated MariaDB schema through a loopback-only test connection. */
@EnabledIfEnvironmentVariable(named = "CALEN_LEASE_TEST_JDBC_URL",
        matches = "jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/calen_lease_verify_[A-Za-z0-9_]+(?:\\?.*)?")
class WorkLeaseMariaDbIntegrationTest {
    DriverManagerDataSource source;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        source = new DriverManagerDataSource(System.getenv("CALEN_LEASE_TEST_JDBC_URL"),
                System.getenv("CALEN_LEASE_TEST_USER"), System.getenv("CALEN_LEASE_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table if not exists app_work_leases (lease_key varchar(240) primary key, owner_token varchar(36) not null, expires_at_epoch_millis bigint not null, index idx_work_leases_expiry(expires_at_epoch_millis)) engine=InnoDB");
        jdbc.update("delete from app_work_leases");
        System.out.println("Lease test database: " + jdbc.queryForObject("select version()", String.class)
                + ", server isolation=" + jdbc.queryForObject("select @@transaction_isolation", String.class));
    }

    @Test void threeFirstClaimsInTheSameIndexGapAllSucceed() throws Exception {
        // Align the real UPDATE statements before INSERT, without injecting an SQL failure.
        // Under InnoDB RR the old algorithm holds three conflicting gap/insert-intention locks.
        var aligned = new AlignedClaimsDataSource(source, 3);
        var service = new WorkLeaseService(aligned, new DataSourceTransactionManager(aligned));
        assertThreeConcurrentClaims(service, aligned);
    }

    @Test void threeClaimsAlsoWorkWithJpaTransactionsAndHikariConnections() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("CALEN_LEASE_TEST_JDBC_URL"));
        config.setUsername(System.getenv("CALEN_LEASE_TEST_USER"));
        config.setPassword(System.getenv("CALEN_LEASE_TEST_PASSWORD"));
        config.setMaximumPoolSize(6);
        config.setConnectionTimeout(10000);
        try (var pool = new HikariDataSource(config)) {
            var aligned = new AlignedClaimsDataSource(pool, 3);
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(aligned);
            factory.setPackagesToScan("com.playdata.calen.common.jobs");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            // No application entities, migrations, external AI, or existing schemas are used.
            factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", "none"));
            factory.afterPropertiesSet();
            try {
                var manager = new JpaTransactionManager(factory.getObject());
                var service = new WorkLeaseService(aligned, manager);
                assertThreeConcurrentClaims(service, aligned);
                var caller = new TransactionTemplate(manager);
                caller.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                var callerJdbc = new JdbcTemplate(aligned);
                caller.executeWithoutResult(status -> {
                    assertThat(connectionIsolation(callerJdbc)).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                    try (var lease = service.tryAcquire("nested-image-job", Duration.ofMinutes(2))) {
                        assertThat(lease).isNotNull();
                        assertThat(connectionIsolation(callerJdbc)).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                    }
                });
                // Returning a connection to Hikari must not change its original session default.
                assertThat(connectionIsolation(callerJdbc)).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            } finally { factory.destroy(); }
        }
    }

    private int connectionIsolation(JdbcTemplate template) {
        return template.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) Connection::getTransactionIsolation);
    }

    private void assertThreeConcurrentClaims(WorkLeaseService service, AlignedClaimsDataSource aligned) throws Exception {
        var workers = Executors.newFixedThreadPool(3);
        var acquired = new CopyOnWriteArrayList<WorkLeaseService.Lease>();
        var failures = new CopyOnWriteArrayList<Throwable>();
        try {
            var tasks = new ArrayList<Future<?>>();
            for (int index = 0; index < 3; index++) {
                int id = index;
                tasks.add(workers.submit(() -> {
                    try {
                        var lease = service.tryAcquire("image-job:" + id, Duration.ofMinutes(2));
                        if (lease != null) acquired.add(lease);
                    } catch (Throwable failure) { failures.add(failure); }
                }));
            }
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
            System.out.println("Aligned claims: success=" + acquired.size() + ", failures=" + failures.size()
                    + ", sqlErrors=" + aligned.sqlErrors);
            assertThat(failures).as("concurrent claims must not be reported as a storage outage").isEmpty();
            assertThat(acquired).hasSize(3);
            assertThat(aligned.claimIsolations).containsOnly(Connection.TRANSACTION_READ_COMMITTED);
        } finally {
            acquired.forEach(WorkLeaseService.Lease::close);
            workers.shutdownNow();
        }
    }

    @Test void realMariaDbLockWaitTimeoutIsRetriedAfterTheBlockingTransactionReleases() throws Exception {
        jdbc.update("insert into app_work_leases values ('blocked-job', 'expired-fixture', 0)");
        var observed = new AlignedClaimsDataSource(source, 1);
        var service = new WorkLeaseService(observed, new DataSourceTransactionManager(observed));
        var worker = Executors.newSingleThreadExecutor();
        try (var blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("select * from app_work_leases where lease_key='blocked-job' for update")) {
                try (var row = lock.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            try {
                var completed = worker.submit(() -> {
                    try (var lease = service.tryAcquire("blocked-job", Duration.ofMinutes(2))) {
                        return lease != null && lease.isValid();
                    }
                });
                assertThat(observed.firstSqlFailure.await(10, TimeUnit.SECONDS)).isTrue();
                blocker.rollback();
                assertThat(completed.get(15, TimeUnit.SECONDS)).isTrue();
                assertThat(observed.sqlErrors).anyMatch(code -> code.endsWith("/1205"));
                assertThat(jdbc.queryForObject("select count(*) from app_work_leases", Integer.class)).isZero();
                System.out.println("Real MariaDB lock timeout: " + observed.sqlErrors + ", retry completed, remaining leases=0");
            } finally { blocker.rollback(); }
        } finally { worker.shutdownNow(); }
    }

    @Test void concurrentSameKeyHasExactlyOneWinnerAndExpiredOwnerCannotReleaseItsReplacement() throws Exception {
        var first = new WorkLeaseService(source, new DataSourceTransactionManager(source));
        var second = new WorkLeaseService(source, new DataSourceTransactionManager(source));
        var workers = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        var acquired = new ArrayList<WorkLeaseService.Lease>();
        try {
            var tasks = new ArrayList<Future<WorkLeaseService.Lease>>();
            for (int index = 0; index < 12; index++) {
                var service = index % 2 == 0 ? first : second;
                tasks.add(workers.submit(() -> { start.await(); return service.tryAcquire("provider", Duration.ofMinutes(2)); }));
            }
            start.countDown();
            for (var task : tasks) { var lease = task.get(20, TimeUnit.SECONDS); if (lease != null) acquired.add(lease); }
            assertThat(acquired).hasSize(1);
            jdbc.update("update app_work_leases set expires_at_epoch_millis=0 where lease_key='provider'");
            var replacement = second.tryAcquire("provider", Duration.ofMinutes(2));
            assertThat(replacement).isNotNull();
            try {
                acquired.get(0).close();
                assertThat(first.tryAcquire("provider", Duration.ofMinutes(2))).isNull();
            } finally { replacement.close(); }
        } finally { acquired.forEach(WorkLeaseService.Lease::close); workers.shutdownNow(); }
        assertThat(jdbc.queryForObject("select count(*) from app_work_leases", Integer.class)).isZero();
    }

    @Test void threeImageSlotsCompleteTwelveRequestsAcrossTwoNodesWithoutErrors() throws Exception {
        jdbc.execute("create table if not exists admin_ops_control_settings (setting_key varchar(128) primary key, setting_value varchar(4000), updated_at timestamp)");
        jdbc.update("delete from admin_ops_control_settings");
        var pool = new LedgerOcrAsyncConfig().ledgerOcrTaskExecutor(3, 100);
        var first = new LedgerImageConcurrencyService(jdbc, pool, new WorkLeaseService(source, new DataSourceTransactionManager(source)));
        var second = new LedgerImageConcurrencyService(jdbc, pool, new WorkLeaseService(source, new DataSourceTransactionManager(source)));
        var entered = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var workers = Executors.newFixedThreadPool(6);
        try {
            var tasks = new ArrayList<Future<Integer>>();
            for (int index = 0; index < 12; index++) {
                int id = index;
                var service = index % 2 == 0 ? first : second;
                tasks.add(workers.submit(() -> service.execute(() -> {
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); return id; }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                    finally { active.decrementAndGet(); }
                })));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(active.get()).isEqualTo(3);
            release.countDown();
            for (int index = 0; index < 12; index++) assertThat(tasks.get(index).get(20, TimeUnit.SECONDS)).isEqualTo(index);
            assertThat(peak.get()).isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from app_work_leases", Integer.class)).isZero();
            System.out.println("Image concurrency: completed=12, peak=" + peak.get() + ", remaining leases=0");
        } finally { release.countDown(); workers.shutdownNow(); pool.shutdown(); }
    }

    private static final class AlignedClaimsDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final CyclicBarrier barrier;
        private final AtomicInteger aligned = new AtomicInteger();
        private final List<String> sqlErrors = new CopyOnWriteArrayList<>();
        private final List<Integer> claimIsolations = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstSqlFailure = new CountDownLatch(1);
        AlignedClaimsDataSource(DataSource delegate, int parties) { this.delegate = delegate; this.barrier = new CyclicBarrier(parties); }
        @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return wrap(delegate.getConnection(user, password)); }
        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(connection, args);
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        boolean claimUpdate = sql.startsWith("update app_work_leases set owner_token");
                        PreparedStatement statement = (PreparedStatement) result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (statementProxy, statementMethod, values) -> {
                            try {
                                if (claimUpdate && statementMethod.getName().equals("executeUpdate")) claimIsolations.add(connection.getTransactionIsolation());
                                Object updated = statementMethod.invoke(statement, values);
                                if (claimUpdate && statementMethod.getName().equals("executeUpdate") && Integer.valueOf(0).equals(updated) && aligned.getAndIncrement() < barrier.getParties()) {
                                    barrier.await(10, TimeUnit.SECONDS);
                                }
                                return updated;
                            } catch (InvocationTargetException failure) {
                                if (failure.getCause() instanceof SQLException sqlFailure) {
                                    sqlErrors.add(sqlFailure.getSQLState() + "/" + sqlFailure.getErrorCode());
                                    firstSqlFailure.countDown();
                                }
                                throw failure.getCause();
                            }
                        });
                    }
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
    }
}
