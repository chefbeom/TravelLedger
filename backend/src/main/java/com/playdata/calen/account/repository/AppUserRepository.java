package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    boolean existsByLoginId(String loginId);

    long countByActiveTrue();

    long countByRole(AppUserRole role);

    long countByRoleAndActiveTrue(AppUserRole role);

    List<AppUser> findAllByOrderByIdAsc();

    List<AppUser> findAllByActiveTrueOrderByDisplayNameAscIdAsc();

    Optional<AppUser> findByLoginId(String loginId);

    @Query("""
            select user
            from AppUser user
            where user.active = true
              and user.id <> :excludedUserId
              and (
                    lower(user.displayName) like lower(concat('%', :query, '%'))
                    or lower(user.loginId) like lower(concat('%', :query, '%'))
              )
            order by user.displayName asc, user.id asc
            """)
    List<AppUser> searchActiveUsersForInvitation(
            @Param("excludedUserId") Long excludedUserId,
            @Param("query") String query,
            Pageable pageable
    );
}
