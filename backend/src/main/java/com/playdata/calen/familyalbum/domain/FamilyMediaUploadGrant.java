package com.playdata.calen.familyalbum.domain;

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

/** No signed URL is stored; category and uploader support later shared-photo reconciliation. */
@Entity
@Table(name = "family_media_upload_grants", indexes = @Index(name = "idx_family_upload_grants_owner", columnList = "owner_id,id"))
@Getter @Setter @NoArgsConstructor
public class FamilyMediaUploadGrant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @Column(name = "category_id", nullable = false) private Long categoryId;
    @Column(name = "object_key", nullable = false, length = 600) private String objectKey;
    @Column(name = "storage_bucket", nullable = false, length = 255) private String storageBucket;
    @Column(name = "signed_at_utc", nullable = false) private LocalDateTime signedAtUtc;
    @Column(name = "expires_at_utc", nullable = false) private LocalDateTime expiresAtUtc;
}
