package com.playdata.calen.recordmap.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "record_map_visits")
@Getter @Setter @NoArgsConstructor
public class PlaceVisit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "place_id", nullable = false) private RecordPlace place;
    @Column(nullable = false, length = 160) private String title;
    @Column(length = 4000) private String memo;
    @Column(name = "visit_date", nullable = false) private LocalDate visitDate;
    @Column(name = "visit_time") private LocalTime visitTime;
    @Version private Long version;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @PrePersist void create() { createdAt = LocalDateTime.now(); }
}
