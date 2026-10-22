package com.playdata.calen.common.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class WorkLeaseRetryTest {
    private static final Duration TTL = Duration.ofMinutes(2);

    @ParameterizedTest
    @CsvSource({"40001,1213", "41000,1205"})
    void lockConflictsRollBackBeforeRetryingInFreshReadCommittedTransactions(String state, int code) {
        var source = fixture(new SQLException("fixture contention", state, code), 2);
        var service = new WorkLeaseService(source, new DataSourceTransactionManager(source));
        try (var lease = service.tryAcquire("image-job", TTL)) {
            assertThat(lease).isNotNull();
            assertThat(source.transactionEvents).containsExactly("begin", "rollback", "begin", "rollback", "begin", "commit");
            assertThat(source.claimConnections).hasSize(3).doesNotHaveDuplicates();
            assertThat(source.claimIsolations).containsOnly(Connection.TRANSACTION_READ_COMMITTED);
        }
        assertThat(source.jdbc.queryForObject("select count(*) from app_work_leases", Integer.class)).isZero();
    }

    @Test void intervalReservationsUseTheSameRetryPolicyAndKeepTheirOwnership() {
        var source = fixture(new SQLException("fixture contention", "40001", 1213), 1);
        var service = new WorkLeaseService(source, new DataSourceTransactionManager(source));
        assertThat(service.tryReserveInterval("geocode", TTL)).isTrue();
        assertThat(service.tryReserveInterval("geocode", TTL)).isFalse();
        assertThat(source.transactionEvents).containsExactly("begin", "rollback", "begin", "commit", "begin", "commit");
        assertThat(source.jdbc.queryForObject("select count(*) from app_work_leases", Integer.class)).isEqualTo(1);
    }

    @Test void repeatedContentionIsBoundedAndDoesNotMasqueradeAsConnectionFailure() {
        var contention = new SQLException("fixture contention", "40001", 1213);
        var source = fixture(contention, 10);
        var service = new WorkLeaseService(source, new DataSourceTransactionManager(source));
        assertThatThrownBy(() -> service.tryAcquire("job", TTL))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("확보가 지연")
                .hasRootCause(contention);
        assertThat(source.transactionEvents).containsExactly("begin", "rollback", "begin", "rollback", "begin", "rollback");
        assertThat(source.jdbc.queryForObject("select count(*) from app_work_leases", Integer.class)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"08006,0", "42000,1142"})
    void connectionAndPermissionFailuresAreNotRetriedOrAllowedThrough(String state, int code) {
        var outage = new SQLException("fixture unavailable", state, code);
        var source = fixture(outage, 10);
        var service = new WorkLeaseService(source, new DataSourceTransactionManager(source));
        assertThatThrownBy(() -> service.tryAcquire("job", TTL))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("저장소에 연결")
                .hasRootCause(outage);
        assertThat(source.transactionEvents).containsExactly("begin", "rollback");
        assertThat(source.jdbc.queryForObject("select count(*) from app_work_leases", Integer.class)).isZero();
    }

    @Test void transactionStartFailureRetainsItsCauseAndDoesNotRetryAnOutage() {
        var source = fixture(null, 0);
        var manager = mock(PlatformTransactionManager.class);
        var outage = new CannotCreateTransactionException("fixture unavailable", new SQLException("fixture", "08001"));
        when(manager.getTransaction(any())).thenThrow(outage);
        var service = new WorkLeaseService(source, manager);
        assertThatThrownBy(() -> service.tryAcquire("job", TTL))
                .isInstanceOf(ServiceUnavailableException.class).hasCause(outage);
        verify(manager, times(1)).getTransaction(any());
        assertThat(source.claimConnections).isEmpty();
    }

    @Test void interruptDuringRetryIsPreservedAndDoesNotLeaveALease() {
        var source = fixture(new SQLException("fixture contention", "40001", 1213), 10);
        source.interruptOnFailure = true;
        var service = new WorkLeaseService(source, new DataSourceTransactionManager(source));
        try {
            assertThatThrownBy(() -> service.tryAcquire("job", TTL)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(source.transactionEvents).containsExactly("begin", "rollback");
        assertThat(source.jdbc.queryForObject("select count(*) from app_work_leases", Integer.class)).isZero();
    }

    @Test void alreadyInterruptedCallerDoesNotStartATransaction() {
        var source = fixture(null, 0);
        var service = new WorkLeaseService(source, new DataSourceTransactionManager(source));
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> service.tryAcquire("job", TTL)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(source.transactionEvents).isEmpty();
    }

    @Test void leaseTransactionDoesNotChangeTheCallersRepeatableReadTransaction() {
        var source = fixture(null, 0);
        var manager = new DataSourceTransactionManager(source);
        var service = new WorkLeaseService(source, manager);
        var callerJdbc = new JdbcTemplate(source);
        var caller = new TransactionTemplate(manager);
        caller.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        caller.executeWithoutResult(status -> {
            assertThat(callerJdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) Connection::getTransactionIsolation))
                    .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            try (var lease = service.tryAcquire("job", TTL)) {
                assertThat(lease).isNotNull();
                assertThat(callerJdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>) Connection::getTransactionIsolation))
                        .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            }
        });
        assertThat(source.claimIsolations).containsExactly(Connection.TRANSACTION_READ_COMMITTED);
    }

    private FaultDataSource fixture(SQLException fault, int count) {
        var delegate = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(delegate);
        jdbc.execute("create table app_work_leases (lease_key varchar(240) primary key, owner_token varchar(36) not null, expires_at_epoch_millis bigint not null)");
        return new FaultDataSource(delegate, jdbc, fault, count);
    }

    /** Real JDBC transactions with a deterministic, statement-level transient SQL failure. */
    private static final class FaultDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final JdbcTemplate jdbc;
        private final SQLException fault;
        private final AtomicInteger remaining;
        private final List<String> transactionEvents = new ArrayList<>();
        private final List<Connection> claimConnections = new ArrayList<>();
        private final List<Integer> claimIsolations = new ArrayList<>();
        private boolean interruptOnFailure;
        FaultDataSource(DataSource delegate, JdbcTemplate jdbc, SQLException fault, int count) {
            this.delegate = delegate; this.jdbc = jdbc; this.fault = fault; this.remaining = new AtomicInteger(count);
        }
        @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return wrap(delegate.getConnection(user, password)); }
        private Connection wrap(Connection connection) throws SQLException {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(connection, args);
                    if (method.getName().equals("setAutoCommit") && Boolean.FALSE.equals(args[0])) transactionEvents.add("begin");
                    if (method.getName().equals("commit") || method.getName().equals("rollback")) transactionEvents.add(method.getName());
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql && sql.startsWith("update app_work_leases set owner_token")) {
                        PreparedStatement statement = (PreparedStatement) result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (statementProxy, statementMethod, values) -> {
                            try {
                                if (statementMethod.getName().equals("executeUpdate")) {
                                    claimConnections.add(connection);
                                    claimIsolations.add(connection.getTransactionIsolation());
                                    if (remaining.getAndDecrement() > 0) {
                                        if (interruptOnFailure) Thread.currentThread().interrupt();
                                        throw fault;
                                    }
                                }
                                return statementMethod.invoke(statement, values);
                            } catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
                    }
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
    }
}
