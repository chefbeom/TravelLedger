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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ledger_classification_rules", indexes = {
        @Index(name = "idx_ledger_classification_rules_owner_active_priority", columnList = "owner_id, active, priority, id"),
        @Index(name = "idx_ledger_classification_rules_owner_keyword", columnList = "owner_id, normalized_keyword")
})
@Getter
@Setter
@NoArgsConstructor
public class LedgerClassificationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false, length = 160)
    private String keyword;

    @Column(name = "normalized_keyword", nullable = false, length = 160)
    private String normalizedKeyword;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", length = 20)
    private EntryType entryType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_group_id", nullable = false)
    private CategoryGroup categoryGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_detail_id")
    private CategoryDetail categoryDetail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_method_id")
    private PaymentMethod paymentMethod;

    @Column(nullable = false)
    private int priority = 100;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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