package com.playdata.calen.ledger.domain;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "recurring_ledger_occurrences",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_recurring_ledger_occurrences_rule_date",
                columnNames = {"rule_id", "scheduled_date"}
        ),
        indexes = {
                @Index(name = "idx_recurring_ledger_occurrences_rule_status", columnList = "rule_id, status, scheduled_date"),
                @Index(name = "idx_recurring_ledger_occurrences_status_date", columnList = "status, scheduled_date")
        })
@Getter
@Setter
@NoArgsConstructor
public class RecurringLedgerOccurrence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private RecurringLedgerRule rule;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecurringLedgerMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecurringLedgerOccurrenceStatus status;

    @Column(name = "created_entry_id")
    private Long createdEntryId;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
