package com.playdata.calen.calendar;

import com.playdata.calen.account.domain.AppUser;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A private user-created event, not a copy of any ledger or travel record. */
@Entity
@Table(name = "calendar_events", indexes = @Index(name = "idx_calendar_events_owner_dates", columnList = "owner_id,start_date,end_date"))
@Getter @Setter @NoArgsConstructor
public class CalendarEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "owner_id", nullable = false) private AppUser owner;
    @Column(nullable = false, length = 160) private String title;
    @Column(name = "start_date", nullable = false) private LocalDate startDate;
    @Column(name = "end_date", nullable = false) private LocalDate endDate;
    @Column(name = "start_time") private LocalTime startTime;
    @Column(name = "end_time") private LocalTime endTime;
    @Column(name = "all_day", nullable = false) private boolean allDay;
    @Column(length = 4000) private String memo;
    @Version @Column(nullable = false) private Long version;
}
