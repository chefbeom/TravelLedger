package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.UserLayoutSetting;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserLayoutSettingRepository extends JpaRepository<UserLayoutSetting, Long> {

    Optional<UserLayoutSetting> findByOwnerIdAndLayoutScope(Long ownerId, String layoutScope);

    @Query("""
            select setting
            from UserLayoutSetting setting
            join fetch setting.owner owner
            where setting.layoutScope = :layoutScope
              and owner.active = true
            """)
    List<UserLayoutSetting> findActiveSettingsByLayoutScope(@Param("layoutScope") String layoutScope);
}
