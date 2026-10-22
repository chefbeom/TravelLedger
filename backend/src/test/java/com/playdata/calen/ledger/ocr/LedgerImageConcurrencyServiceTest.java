package com.playdata.calen.ledger.ocr;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.jobs.WorkLeaseService;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerImageConcurrencyServiceTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager transactions;
    ThreadPoolTaskExecutor executor;
    LedgerImageConcurrencyService service;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:image-limit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        transactions = new DataSourceTransactionManager(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table admin_ops_control_settings (setting_key varchar(128) primary key, setting_value varchar(4000), updated_at timestamp)");
        jdbc.execute("create table app_work_leases (lease_key varchar(240) primary key, owner_token varchar(36), expires_at_epoch_millis bigint)");
        executor = new LedgerOcrAsyncConfig().ledgerOcrTaskExecutor(6, 100);
        service = new LedgerImageConcurrencyService(jdbc, executor, new WorkLeaseService(source, transactions));
        service.refresh();
    }
    @AfterEach void cleanup() { executor.shutdown(); }

    @Test void defaultThreeOverridesOldWorkerEnvAndSavedLimitSurvivesNewServiceInstances() {
        assertThat(service.getSettings().maxConcurrent()).isEqualTo(3);
        assertThat(executor.getCorePoolSize()).isEqualTo(3);
        service.update(5);
        assertThat(executor.getMaxPoolSize()).isEqualTo(5);
        var restarted = new LedgerImageConcurrencyService(jdbc, executor, mock(WorkLeaseService.class));
        assertThat(restarted.getSettings().maxConcurrent()).isEqualTo(5);
        service.update(1);
        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(1);
    }

    @Test void invalidValuesAndFailedPersistenceNeverApplyChanges() {
        for (Integer value : new Integer[]{null, 0, -1, 21}) {
            assertThatThrownBy(() -> service.update(value)).isInstanceOf(BadRequestException.class);
        }
        var brokenJdbc = mock(JdbcTemplate.class);
        when(brokenJdbc.update(anyString(), any(Object[].class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        var broken = new LedgerImageConcurrencyService(brokenJdbc, executor, mock(WorkLeaseService.class));
        assertThatThrownBy(() -> broken.update(7)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(executor.getMaxPoolSize()).isEqualTo(3);
    }

    @Test void rollbackDoesNotChangeTheRuntimeLimit() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.update(7);
            assertThat(executor.getCorePoolSize()).isEqualTo(3);
            status.setRollbackOnly();
        });
        assertThat(service.getSettings().maxConcurrent()).isEqualTo(3);
        new TransactionTemplate(transactions).executeWithoutResult(status -> service.update(4));
        assertThat(executor.getCorePoolSize()).isEqualTo(4);
    }

    @Test void threeSharedSlotsBoundMultipleCallersAndReleaseAfterSuccessOrFailure() throws Exception {
        var workers = Executors.newFixedThreadPool(8);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(3);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var second = new LedgerImageConcurrencyService(jdbc, executor,
                new WorkLeaseService(jdbc.getDataSource(), transactions));
        var tasks = new ArrayList<Future<Integer>>();
        try {
            for (int index = 0; index < 8; index++) {
                int id = index;
                var policy = index % 2 == 0 ? service : second;
                tasks.add(workers.submit(() -> policy.execute(() -> {
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                        if (id == 0) throw new IllegalArgumentException("fixture failure");
                        return id;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException();
                    } finally { active.decrementAndGet(); }
                })));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(active.get()).isEqualTo(3);
            release.countDown();
            for (int index = 0; index < tasks.size(); index++) {
                if (index == 0) assertThatThrownBy(() -> tasks.get(0).get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                else assertThat(tasks.get(index).get(10, TimeUnit.SECONDS)).isEqualTo(index);
            }
            assertThat(peak.get()).isEqualTo(3);
            assertThat(jdbc.queryForObject("select count(*) from app_work_leases", Integer.class)).isZero();
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void interruptedWaitDoesNotStartAnExtraAnalysisOrLeakSlots() throws Exception {
        service.update(1);
        var source = jdbc.getDataSource();
        var holder = new WorkLeaseService(source, transactions).tryAcquire("image-analysis-slot:0", java.time.Duration.ofMinutes(1));
        var entered = new CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            entered.countDown();
            try { service.execute(() -> { throw new AssertionError("must not run"); }); }
            catch (Throwable caught) { failure.set(caught); }
        });
        try {
            thread.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            thread.interrupt();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(CancellationException.class);
        } finally { holder.close(); thread.interrupt(); }
        assertThat(jdbc.queryForObject("select count(*) from app_work_leases", Integer.class)).isZero();
    }
}
