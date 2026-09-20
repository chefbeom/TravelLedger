package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.SocialAccount;
import com.playdata.calen.account.domain.SocialLoginProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    @Query("""
            select account
            from SocialAccount account
            join fetch account.user
            where account.provider = :provider
              and account.providerUserId = :providerUserId
            """)
    Optional<SocialAccount> findWithUserByProviderAndProviderUserId(
            @Param("provider") SocialLoginProvider provider,
            @Param("providerUserId") String providerUserId
    );

    boolean existsByProviderAndProviderUserId(SocialLoginProvider provider, String providerUserId);
}
