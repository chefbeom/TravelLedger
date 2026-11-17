package com.playdata.calen.account.withdrawal;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WithdrawalCleanupTaskRepository extends JpaRepository<WithdrawalCleanupTask, String> {
    Optional<WithdrawalCleanupTask> findByRequestIdAndTaskKeyHash(String requestId, String taskKeyHash);

    Page<WithdrawalCleanupTask> findAllByRequestIdOrderByCreatedAtAscIdAsc(String requestId, Pageable pageable);

    @Query("""
            select task.requestId, task.kind, task.state, count(task)
            from WithdrawalCleanupTask task
            where task.requestId in :requestIds
            group by task.requestId, task.kind, task.state
            order by task.requestId, task.kind, task.state
            """)
    List<Object[]> summarizeByRequestIds(@Param("requestIds") Collection<String> requestIds);

    @Query("""
            select task.id from WithdrawalCleanupTask task
            where task.kind in :kinds
              and ((task.state = :pending and task.nextAttemptAt <= :now)
                or (task.state = :processing and task.leaseUntil <= :now))
            order by task.nextAttemptAt, task.createdAt, task.id
            """)
    List<String> findDueIds(@Param("now") LocalDateTime now,
                            @Param("pending") WithdrawalCleanupTask.State pending,
                            @Param("processing") WithdrawalCleanupTask.State processing,
                            @Param("kinds") Collection<WithdrawalCleanupKind> kinds,
                            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from WithdrawalCleanupTask task where task.id = :id")
    Optional<WithdrawalCleanupTask> lockById(@Param("id") String id);
}
