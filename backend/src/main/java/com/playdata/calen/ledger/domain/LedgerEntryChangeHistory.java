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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ledger_entry_change_histories")
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntryChangeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LedgerEntryChangeAction action;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private int entryCount;

    @Column(nullable = false, length = 500)
    private String summary;

    @Lob
    @Column(nullable = false)
    private String beforeSnapshotJson;

    @Lob
    @Column(nullable = false)
    private String afterSnapshotJson;
}
