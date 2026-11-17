package com.playdata.calen.calendar;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarMapGrantRepository extends JpaRepository<CalendarMapGrant, Long> {
    Optional<CalendarMapGrant> findByViewerIdAndShareId(Long viewer, Long shareId);
    Optional<CalendarMapGrant> findByIdAndViewerId(Long id, Long viewer);
}
