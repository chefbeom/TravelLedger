package com.playdata.calen.account.withdrawal;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountWithdrawalRequestRepository extends JpaRepository<AccountWithdrawalRequest, String> {
    Optional<AccountWithdrawalRequest> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
}
