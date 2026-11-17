package com.playdata.calen.drive.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Tracks an issued object location and its expiry, never the presigned URL or its signature. */
@Entity
@Table(name = "drive_upload_grants", indexes = @Index(name = "idx_drive_upload_grants_owner", columnList = "owner_id,id"))
@Getter @Setter @NoArgsConstructor
public class DriveUploadGrant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @Column(name = "object_key", nullable = false, length = 600) private String objectKey;
    @Column(name = "storage_bucket", nullable = false, length = 255) private String storageBucket;
    @Column(name = "signed_at_utc", nullable = false) private LocalDateTime signedAtUtc;
    @Column(name = "expires_at_utc", nullable = false) private LocalDateTime expiresAtUtc;
}
