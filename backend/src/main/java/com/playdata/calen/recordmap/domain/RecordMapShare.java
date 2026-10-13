package com.playdata.calen.recordmap.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "record_map_shares")
@Getter @Setter @NoArgsConstructor
public class RecordMapShare {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "collection_id", nullable = false) private PlaceCollection collection;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(name = "include_visits", nullable = false) private boolean includeVisits;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @PrePersist void create() { createdAt = LocalDateTime.now(); }
}
