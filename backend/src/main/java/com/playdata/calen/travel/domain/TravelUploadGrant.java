package com.playdata.calen.travel.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Issued object location and expiry, never the presigned URL or its credentials. */
@Entity
@Table(name = "travel_upload_grants", indexes = @Index(name = "idx_travel_upload_grants_owner", columnList = "owner_id,id"))
@Getter @Setter @NoArgsConstructor
public class TravelUploadGrant {
    public enum Kind { MEDIA, THUMBNAIL, ROUTE_GPX }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @Column(name = "plan_id", nullable = false) private Long planId;
    @Column(name = "source_id", nullable = false) private Long sourceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Kind kind;
    @Column(name = "object_key", nullable = false, length = 600) private String objectKey;
    @Column(name = "original_key", length = 600) private String originalKey;
    @Column(name = "storage_bucket", nullable = false, length = 255) private String storageBucket;
    @Column(name = "signed_at_utc", nullable = false) private LocalDateTime signedAtUtc;
    @Column(name = "expires_at_utc", nullable = false) private LocalDateTime expiresAtUtc;
}
