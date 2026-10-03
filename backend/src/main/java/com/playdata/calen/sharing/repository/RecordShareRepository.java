package com.playdata.calen.sharing.repository;

import com.playdata.calen.sharing.domain.*;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RecordShareRepository extends JpaRepository<RecordShare, Long> {
    String ACTIVE_SENDER = """
            s.sender.active = true and exists (
              select g.id from TravelShareGroup g where g.id = s.groupId and
              (g.owner.id = s.sender.id or exists (
                select m.id from TravelShareGroupMember m where m.group = g and m.member.id = s.sender.id))
            )
            """;
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RecordShare s where s.id = :id")
    Optional<RecordShare> findLockedById(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RecordShare s where s.kind = :kind and s.sourceId = :sourceId and s.recipient.id = :recipientId")
    Optional<RecordShare> findLockedByKindAndSourceIdAndRecipientId(@Param("kind") RecordShareKind kind,
            @Param("sourceId") Long sourceId, @Param("recipientId") Long recipientId);
    @EntityGraph(attributePaths = {"sender", "recipient"})
    @Query("""
            select s from RecordShare s where s.kind = :kind and s.groupId in :groups
              and ((:sent = true and s.sender.id = :userId) or (:sent = false and s.recipient.id = :userId))
              and (:status is null or s.status = :status) and
            """ + ACTIVE_SENDER)
    Page<RecordShare> findRequests(@Param("userId") Long userId, @Param("kind") RecordShareKind kind,
                                   @Param("sent") boolean sent, @Param("status") RecordShareStatus status,
                                   @Param("groups") List<Long> groups, Pageable pageable);
    @Query("select count(s) from RecordShare s where s.recipient.id = :userId and s.kind = :kind and s.status = 'PENDING' and s.groupId in :groups and " + ACTIVE_SENDER)
    long countPending(@Param("userId") Long userId, @Param("kind") RecordShareKind kind, @Param("groups") List<Long> groups);
}
