package com.playdata.calen.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {
    @Query("select e from CalendarEvent e where e.owner.id = :owner and e.startDate <= :end and e.endDate >= :start order by e.startDate, e.startTime, e.id")
    List<CalendarEvent> findOverlapping(@Param("owner") Long owner, @Param("start") LocalDate start, @Param("end") LocalDate end);
    Optional<CalendarEvent> findByIdAndOwnerId(Long id, Long ownerId);
}
