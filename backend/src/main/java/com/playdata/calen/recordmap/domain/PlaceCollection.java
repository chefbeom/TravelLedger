package com.playdata.calen.recordmap.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "record_map_collections")
@Getter @Setter @NoArgsConstructor
public class PlaceCollection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @Column(nullable = false, length = 100) private String name;
    @Column(length = 1000) private String memo;
    @Column(name = "color_hex", nullable = false, length = 7) private String colorHex = "#009E8C";
    @Version private Long version;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @PrePersist void create() { createdAt = LocalDateTime.now(); }
}
