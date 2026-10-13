package com.playdata.calen.recordmap.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "record_map_places", uniqueConstraints = @UniqueConstraint(name = "uk_record_map_travel_source", columnNames = {"owner_id", "source_travel_record_id"}))
@Getter @Setter @NoArgsConstructor
public class RecordPlace {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @Column(nullable = false, length = 160) private String name;
    @Column(length = 2000) private String memo;
    @Column(length = 80) private String country;
    @Column(length = 120) private String region;
    @Column(nullable = false) private Double latitude;
    @Column(nullable = false) private Double longitude;
    @Column(name = "manually_visited", nullable = false) private boolean manuallyVisited;
    @Column(name = "source_travel_record_id") private Long sourceTravelRecordId;
    @ManyToMany @BatchSize(size = 100)
    @JoinTable(name = "record_map_collection_places", joinColumns = @JoinColumn(name = "place_id"), inverseJoinColumns = @JoinColumn(name = "collection_id"))
    private Set<PlaceCollection> collections = new LinkedHashSet<>();
    @Version private Long version;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @PrePersist void create() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate void update() { updatedAt = LocalDateTime.now(); }
}
