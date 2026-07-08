package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelMapShareLink;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelMapShareLinkRepository extends JpaRepository<TravelMapShareLink, Long> {

    Optional<TravelMapShareLink> findByTokenAndActiveTrue(String token);

    boolean existsByToken(String token);
}
