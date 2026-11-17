package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from AppUser user where user.id = :id and user.active = true")
    Optional<AppUser> findActiveForPreferenceUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from AppUser user where user.id = :id")
    Optional<AppUser> lockForAccountSecurityUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select user from AppUser user where user.id = :id")
    Optional<AppUser> lockForAccountSecurityRead(@Param("id") Long id);

    boolean existsByLoginId(String loginId);

    boolean existsByLoginIdIgnoreCase(String loginId);

    boolean existsByIdAndActiveTrue(Long id);

    boolean existsByIdAndRoleAndActiveTrue(Long id, AppUserRole role);

    long countByActiveTrue();

    long countByRole(AppUserRole role);

    long countByRoleAndActiveTrue(AppUserRole role);

    List<AppUser> findAllByOrderByIdAsc();

    List<AppUser> findAllByActiveTrueOrderByDisplayNameAscIdAsc();

    List<AppUser> findAllByRoleAndActiveTrueOrderByIdAsc(AppUserRole role);

    @Query("select user.id from AppUser user where user.role = :role and user.active = true order by user.id")
    List<Long> findActiveUserIdsByRole(@Param("role") AppUserRole role);

    Optional<AppUser> findByLoginId(String loginId);

    Optional<AppUser> findByEmailIgnoreCase(String email);

    @Query("select user.id from AppUser user where lower(user.email) = lower(:email)")
    Optional<Long> findIdByEmailIgnoreCase(@Param("email") String email);

    boolean existsByEmailIgnoreCase(String email);

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
