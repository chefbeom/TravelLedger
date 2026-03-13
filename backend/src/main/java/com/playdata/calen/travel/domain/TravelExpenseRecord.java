package com.playdata.calen.travel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "travel_expense_records")
@Getter
@Setter
@NoArgsConstructor
public class TravelExpenseRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private TravelPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TravelRecordType recordType = TravelRecordType.LEDGER;

    @Column(nullable = false)
    private LocalDate expenseDate;

    private LocalTime expenseTime;

    @Column(nullable = false, length = 80)
    private String category;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currencyCode = "KRW";

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal exchangeRateToKrw = BigDecimal.ONE;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amountKrw = BigDecimal.ZERO;

    @Column(length = 80)
    private String country;

    @Column(length = 120)
    private String region;

    @Column(length = 160)
    private String placeName;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    private Boolean sharedWithCommunity = false;

    @Column(length = 500)
    private String memo;
}
