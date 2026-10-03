package com.playdata.calen.sharing.domain;

import com.playdata.calen.account.domain.AppUser;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "record_shares", uniqueConstraints = @UniqueConstraint(name = "uk_record_share_source_recipient", columnNames = {"kind", "source_id", "recipient_id"}), indexes = {
        @Index(name = "idx_record_share_inbox", columnList = "recipient_id, kind, status, created_at"),
        @Index(name = "idx_record_share_sent", columnList = "sender_id, kind, created_at")
})
@Getter @Setter @NoArgsConstructor
public class RecordShare {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long groupId;
    @Column(nullable = false, length = 80)
    private String groupName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RecordShareKind kind;
    @Column(nullable = false)
    private Long sourceId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "sender_id", nullable = false)
    private AppUser sender;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "recipient_id", nullable = false)
    private AppUser recipient;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RecordShareStatus status = RecordShareStatus.PENDING;
    @Column(nullable = false, length = 120)
    private String title;
    @Lob @Column(columnDefinition = "TEXT")
    private String snapshotJson;
    // No foreign key: independently imported entries survive source/group removal.
    private Long importedLedgerEntryId;
    @Column(nullable = false)
    private LocalDateTime createdAt;
    private LocalDateTime respondedAt;
}
