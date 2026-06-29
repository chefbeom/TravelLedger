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
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ledger_ai_analysis_histories", indexes = {
        @Index(name = "idx_ledger_ai_history_owner_created", columnList = "owner_id, created_at, id"),
        @Index(name = "idx_ledger_ai_history_owner_range", columnList = "owner_id, from_date, to_date"),
        @Index(name = "idx_ledger_ai_history_owner_mode", columnList = "owner_id, mode, period_type")
})
@Getter
@Setter
@NoArgsConstructor
public class LedgerAiAnalysisHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LedgerAiAnalysisMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 20)
    private LedgerAiAnalysisPeriod periodType;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison_preset", length = 40)
    private LedgerAiComparisonPreset comparisonPreset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LedgerAiAnalysisStatus status;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "compare_from_date")
    private LocalDate compareFromDate;

    @Column(name = "compare_to_date")
    private LocalDate compareToDate;

    @Column(nullable = false, length = 80)
    private String model;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(length = 2000)
    private String summary;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Lob
    @Column(name = "request_payload_json", columnDefinition = "LONGTEXT")
    private String requestPayloadJson;

    @Lob
    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
