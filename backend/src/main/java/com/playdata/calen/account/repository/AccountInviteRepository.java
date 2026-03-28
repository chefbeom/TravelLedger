package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.AccountInvite;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountInviteRepository extends JpaRepository<AccountInvite, Long> {

    Optional<AccountInvite> findByTokenHash(String tokenHash);

    @Query("""
            select invite
            from AccountInvite invite
            join fetch invite.createdBy
            where invite.tokenHash = :tokenHash
            """)
    Optional<AccountInvite> findWithCreatorByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select invite
            from AccountInvite invite
            join fetch invite.createdBy
            where invite.tokenHash = :tokenHash
            """)
    Optional<AccountInvite> findForUpdateByTokenHash(@Param("tokenHash") String tokenHash);
}
