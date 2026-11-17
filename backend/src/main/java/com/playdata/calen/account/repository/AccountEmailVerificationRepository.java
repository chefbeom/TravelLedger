package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.AccountEmailVerification;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountEmailVerificationRepository extends JpaRepository<AccountEmailVerification, Long> {
    Optional<AccountEmailVerification> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("select token.user.id from AccountEmailVerification token where token.tokenHash = :hash")
    Optional<Long> findUserIdByTokenHash(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from AccountEmailVerification token join fetch token.user where token.tokenHash = :hash")
    Optional<AccountEmailVerification> findForUpdateByTokenHash(@Param("hash") String hash);

    @Modifying
    @Query("update AccountEmailVerification token set token.usedAt = :now where token.user.id = :userId and token.usedAt is null")
    int invalidateOpenTokens(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
