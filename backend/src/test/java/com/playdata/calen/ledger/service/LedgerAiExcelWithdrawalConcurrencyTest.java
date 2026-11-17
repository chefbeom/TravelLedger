package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.exception.TooManyRequestsException;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisHistoryWriter;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.ledger.repository.LedgerExcelAnalysisJobRepository;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false, properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:excel-withdrawal-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@Import({LedgerAiExcelJobWriter.class, LedgerAiAnalysisHistoryWriter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerAiExcelWithdrawalConcurrencyTest {
    @Autowired AppUserRepository users;
    @Autowired LedgerExcelAnalysisJobRepository jobs;
    @Autowired LedgerAiExcelJobWriter writer;
    @Autowired LedgerAiAnalysisHistoryWriter analysisWriter;
    @Autowired LedgerAiAnalysisHistoryRepository analysisHistories;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void admissionWaitsForDeactivationAndDoesNotCreateAJobAfterward() throws Exception {
        Long ownerId = user();
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var owner = users.lockForAccountSecurityUpdate(ownerId).orElseThrow();
                owner.setActive(false);
                users.saveAndFlush(owner);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var admission = pool.submit(() -> writer.create(ownerId, "private workbook"));
            assertThatThrownBy(() -> admission.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> admission.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThat(jobs.countByOwnerIdAndStatus(ownerId, "PROCESSING")).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void deactivationWaitsForAdmissionAndCanRemoveTheCommittedJob() throws Exception {
        Long ownerId = user();
        var pool = Executors.newFixedThreadPool(2);
        var created = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var admission = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var job = writer.create(ownerId, "private workbook");
                created.countDown();
                await(release);
                return job.getId();
            }));
            assertThat(created.await(5, TimeUnit.SECONDS)).isTrue();
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var owner = users.lockForAccountSecurityUpdate(ownerId).orElseThrow();
                owner.setActive(false);
                users.saveAndFlush(owner);
                jobs.removeForWithdrawal(ownerId);
            }));
            assertThatThrownBy(() -> deactivate.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            String jobId = admission.get(5, TimeUnit.SECONDS);
            deactivate.get(5, TimeUnit.SECONDS);
            assertThat(jobs.findById(jobId)).isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void admissionEnforcesTheExistingFiveJobLimitUnderTheAccountLock() {
        Long ownerId = user();
        for (int i = 0; i < 5; i++) writer.create(ownerId, "workbook " + i);
        assertThatThrownBy(() -> writer.create(ownerId, "sixth workbook"))
                .isInstanceOf(TooManyRequestsException.class);
        assertThat(jobs.countByOwnerIdAndStatus(ownerId, "PROCESSING")).isEqualTo(5);
    }

    @Test void analysisAdmissionWaitingOnDeactivationCannotCreateHistory() throws Exception {
        Long ownerId = user();
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var owner = users.lockForAccountSecurityUpdate(ownerId).orElseThrow();
                owner.setActive(false);
                users.saveAndFlush(owner);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var admission = pool.submit(() -> analysisWriter.create(ownerId, analysisDraft()));
            assertThatThrownBy(() -> admission.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            deactivate.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> admission.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThat(jdbc.queryForObject("select count(*) from ledger_ai_analysis_histories where owner_id = ?", Integer.class, ownerId)).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void deactivationWaitsForAnalysisAdmissionBeforeRemovingHistory() throws Exception {
        Long ownerId = user();
        var pool = Executors.newFixedThreadPool(2);
        var created = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var admission = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var history = analysisWriter.create(ownerId, analysisDraft());
                created.countDown();
                await(release);
                return history.getId();
            }));
            assertThat(created.await(5, TimeUnit.SECONDS)).isTrue();
            var deactivate = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var owner = users.lockForAccountSecurityUpdate(ownerId).orElseThrow();
                owner.setActive(false);
                users.saveAndFlush(owner);
                analysisHistories.removeForWithdrawal(ownerId);
            }));
            assertThatThrownBy(() -> deactivate.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            Long historyId = admission.get(5, TimeUnit.SECONDS);
            deactivate.get(5, TimeUnit.SECONDS);
            assertThat(analysisHistories.findById(historyId)).isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private LedgerAiAnalysisHistory analysisDraft() {
        var draft = new LedgerAiAnalysisHistory();
        draft.setMode(LedgerAiAnalysisMode.PERIOD); draft.setPeriodType(LedgerAiAnalysisPeriod.MONTH);
        draft.setStatus(LedgerAiAnalysisStatus.PROCESSING); draft.setFromDate(LocalDate.now().minusDays(1));
        draft.setToDate(LocalDate.now()); draft.setModel("test"); draft.setProvider("test"); draft.setTitle("private analysis");
        draft.setRequestPayloadJson("{\"private\":true}");
        return draft;
    }

    private Long user() {
        var user = new AppUser();
        user.setLoginId("excel_" + UUID.randomUUID());
        user.setDisplayName("엑셀 검증 사용자");
        return users.saveAndFlush(user).getId();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test lock timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
