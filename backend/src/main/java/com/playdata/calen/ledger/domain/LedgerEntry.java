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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ledger_entries", indexes = {
        @Index(name = "idx_ledger_entries_owner_deleted_date_id", columnList = "owner_id, deleted_at, entry_date, id"),
        @Index(name = "idx_ledger_entries_owner_deleted_type_date", columnList = "owner_id, deleted_at, entry_type, entry_date"),
        @Index(name = "idx_ledger_entries_owner_title", columnList = "owner_id, title"),
        @Index(name = "idx_ledger_entries_owner_memo", columnList = "owner_id, memo"),
        @Index(name = "idx_ledger_entries_owner_category_date", columnList = "owner_id, category_group_id, category_detail_id, entry_date"),
        @Index(name = "idx_ledger_entries_owner_payment_date", columnList = "owner_id, payment_method_id, entry_date")
})
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false)
    private LocalDate entryDate;

    @Column
    private LocalTime entryTime;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(length = 500)
    private String memo;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "foreign_currency_code", length = 3)
    private String foreignCurrencyCode;

    @Column(name = "foreign_amount", precision = 18, scale = 4)
    private BigDecimal foreignAmount;

    @Column(name = "exchange_rate_to_krw", precision = 18, scale = 6)
    private BigDecimal exchangeRateToKrw;

    @Column(name = "exchange_rate_date")
    private LocalDate exchangeRateDate;

    @Column(name = "exchange_rate_provider", length = 40)
    private String exchangeRateProvider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntryType entryType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_group_id", nullable = false)
    private CategoryGroup categoryGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_detail_id")
    private CategoryDetail categoryDetail;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_method_id", nullable = false)
    private PaymentMethod paymentMethod;

    @Column(name = "travel_plan_id")
    private Long travelPlanId;

    @Column(name = "travel_record_id")
    private Long travelRecordId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
