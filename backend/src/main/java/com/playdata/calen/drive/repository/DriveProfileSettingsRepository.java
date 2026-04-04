package com.playdata.calen.drive.repository;

import com.playdata.calen.drive.domain.DriveProfileSettings;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriveProfileSettingsRepository extends JpaRepository<DriveProfileSettings, Long> {

    Optional<DriveProfileSettings> findByUser_Id(Long userId);
}
