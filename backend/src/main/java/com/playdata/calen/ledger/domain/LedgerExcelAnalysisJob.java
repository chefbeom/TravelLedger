package com.playdata.calen.ledger.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ledger_excel_analysis_jobs", indexes = {
        @Index(name = "idx_excel_jobs_owner_status", columnList = "owner_id, status"),
        @Index(name = "idx_excel_jobs_status_created", columnList = "status, created_at")})
@Getter @Setter @NoArgsConstructor
public class LedgerExcelAnalysisJob {
    @Id @Column(length = 36) private String id;
    @Version private Long version;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @Column(nullable = false, length = 20) private String status = "PROCESSING";
    @Lob @Column(name = "input_json", columnDefinition = "LONGTEXT") private String inputJson;
    @Lob @Column(name = "result_json", columnDefinition = "LONGTEXT") private String resultJson;
    @Column(name = "error_message", length = 500) private String errorMessage;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
}
