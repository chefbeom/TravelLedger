package com.playdata.calen.recordmap.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "record_map_photos")
@Getter @Setter @NoArgsConstructor
public class PlacePhoto {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "visit_id", nullable = false) private PlaceVisit visit;
    @Column(name = "original_name", nullable = false, length = 220) private String originalName;
    @Column(name = "storage_path", nullable = false, length = 600) private String storagePath;
    @Column(name = "content_type", nullable = false, length = 120) private String contentType;
    @Column(name = "file_size", nullable = false) private long fileSize;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @PrePersist void create() { createdAt = LocalDateTime.now(); }
}
