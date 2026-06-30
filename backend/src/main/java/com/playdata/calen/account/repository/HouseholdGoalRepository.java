package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.HouseholdGoal;
import com.playdata.calen.account.domain.HouseholdGoalStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseholdGoalRepository extends JpaRepository<HouseholdGoal, Long> {

    List<HouseholdGoal> findAllByOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId);

    List<HouseholdGoal> findAllByOwnerIdAndStatusNotOrderByCreatedAtDescIdDesc(Long ownerId, HouseholdGoalStatus status);

    Optional<HouseholdGoal> findByIdAndOwnerId(Long id, Long ownerId);
}