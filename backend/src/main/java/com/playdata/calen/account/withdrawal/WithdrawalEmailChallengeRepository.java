package com.playdata.calen.account.withdrawal;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WithdrawalEmailChallengeRepository extends JpaRepository<WithdrawalEmailChallenge, Long> {
    Optional<WithdrawalEmailChallenge> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("select challenge.user.id from WithdrawalEmailChallenge challenge where challenge.tokenHash = :hash")
    Optional<Long> findUserIdByTokenHash(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select challenge from WithdrawalEmailChallenge challenge where challenge.tokenHash = :hash")
    Optional<WithdrawalEmailChallenge> findForUpdateByTokenHash(@Param("hash") String hash);

    @Modifying
    @Query("update WithdrawalEmailChallenge challenge set challenge.usedAt = :now where challenge.user.id = :userId and challenge.usedAt is null")
    int invalidateOpenTokens(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
