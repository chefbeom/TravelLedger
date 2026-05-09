package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelShareGroup;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelShareGroupRepository extends JpaRepository<TravelShareGroup, Long> {

    List<TravelShareGroup> findAllByOwnerIdOrderByUpdatedAtDescIdDesc(Long ownerId);

    Optional<TravelShareGroup> findByIdAndOwnerId(Long id, Long ownerId);

    Optional<TravelShareGroup> findByOwnerIdAndNameIgnoreCase(Long ownerId, String name);
}
