package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.EmailVerificationToken;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    @Query("select token.user.id from EmailVerificationToken token where token.tokenHash = :tokenHash")
    Optional<Long> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select token
            from EmailVerificationToken token
            join fetch token.user
            where token.tokenHash = :tokenHash
            """)
    Optional<EmailVerificationToken> findForUpdateByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationToken> findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(Long userId);

    @Query("""
            select token.user.id from EmailVerificationToken token
            where token.cleanupEligible = true and token.usedAt is null
              and token.createdAt <= :cutoff and token.user.id > :afterId
              and token.user.active = false and token.user.emailVerified = false
              and token.user.expiredEmailSignup = false
            order by token.user.id
            """)
    List<Long> findCleanupCandidates(@Param("cutoff") LocalDateTime cutoff,
                                     @Param("afterId") Long afterId, Pageable pageable);

    @Modifying
    @Query("delete from EmailVerificationToken token where token.user.id = :userId")
    int deleteAllForUser(@Param("userId") Long userId);

    @Modifying
    @Query("""
            update EmailVerificationToken token
               set token.usedAt = :usedAt
             where token.user.id = :userId
               and token.usedAt is null
            """)
    int invalidateOpenTokens(@Param("userId") Long userId, @Param("usedAt") LocalDateTime usedAt);
}
