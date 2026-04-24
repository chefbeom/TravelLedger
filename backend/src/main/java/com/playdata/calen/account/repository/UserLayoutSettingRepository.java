package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.UserLayoutSetting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLayoutSettingRepository extends JpaRepository<UserLayoutSetting, Long> {

    Optional<UserLayoutSetting> findByOwnerIdAndLayoutScope(Long ownerId, String layoutScope);
}
