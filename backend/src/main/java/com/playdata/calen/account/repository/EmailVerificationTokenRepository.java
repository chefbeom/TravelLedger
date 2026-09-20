package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.EmailVerificationToken;
import java.time.LocalDateTime;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select token
            from EmailVerificationToken token
            join fetch token.user
            where token.tokenHash = :tokenHash
            """)
    Optional<EmailVerificationToken> findForUpdateByTokenHash(@Param("tokenHash") String tokenHash);

    Optional<EmailVerificationToken> findTopByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Query("""
            update EmailVerificationToken token
               set token.usedAt = :usedAt
             where token.user.id = :userId
               and token.usedAt is null
            """)
    int invalidateOpenTokens(@Param("userId") Long userId, @Param("usedAt") LocalDateTime usedAt);
}
