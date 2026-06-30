package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.UserNotification;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    Page<UserNotification> findAllByOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId, Pageable pageable);

    Page<UserNotification> findAllByOwnerIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(Long ownerId, Pageable pageable);

    Optional<UserNotification> findByIdAndOwnerId(Long id, Long ownerId);

    long countByOwnerIdAndReadAtIsNull(Long ownerId);

    boolean existsByOwnerIdAndTypeAndTargetUrlAndReadAtIsNull(Long ownerId, String type, String targetUrl);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserNotification notification
            set notification.readAt = :readAt
            where notification.ownerId = :ownerId
              and notification.readAt is null
            """)
    int markAllUnreadAsRead(
            @Param("ownerId") Long ownerId,
            @Param("readAt") LocalDateTime readAt
    );
}