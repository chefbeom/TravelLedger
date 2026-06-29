package com.playdata.calen.drive.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "drive_download_link_access_logs")
@Getter
@Setter
@NoArgsConstructor
public class DriveDownloadLinkAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_id")
    private Long linkId;

    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "token_fingerprint", nullable = false, length = 64)
    private String tokenFingerprint;

    @Column(name = "client_address", length = 64)
    private String clientAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "accessed_at", nullable = false)
    private LocalDateTime accessedAt;

    @PrePersist
    public void prePersist() {
        if (accessedAt == null) {
            accessedAt = LocalDateTime.now();
        }
    }
}