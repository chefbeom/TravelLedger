package com.playdata.calen.ledger.domain;

import com.playdata.calen.account.domain.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ledger_image_analysis_requests", indexes = {
        @Index(name = "idx_ledger_image_analysis_owner_created", columnList = "owner_id, created_at, id"),
        @Index(name = "idx_ledger_image_analysis_owner_status", columnList = "owner_id, status, created_at"),
        @Index(name = "idx_ledger_image_analysis_owner_type", columnList = "owner_id, document_type, created_at"),
        @Index(name = "idx_ledger_image_analysis_owner_client", columnList = "owner_id, client_request_id")
})
@Getter
@Setter
@NoArgsConstructor
public class LedgerImageAnalysisRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LedgerImageAnalysisStatus status = LedgerImageAnalysisStatus.PROCESSING;

    @Column(name = "document_type", nullable = false, length = 30)
    private String documentType = "AUTO";

    @Column(name = "client_request_id", length = 120)
    private String clientRequestId;

    @Column(name = "file_name", length = 260)
    private String fileName;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "image_object_key", length = 600)
    private String imageObjectKey;

    @Column(name = "image_stored_at")
    private LocalDateTime imageStoredAt;

    @Column(name = "summary", length = 500)
    private String summary;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Lob
    @Column(name = "raw_text", columnDefinition = "LONGTEXT")
    private String rawText;

    @Lob
    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
