package com.playdata.calen.calendar;

import com.playdata.calen.account.domain.AppUser;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Proof that this account presented the bearer link; the bearer token itself is never stored. */
@Entity
@Table(name = "calendar_map_grants", uniqueConstraints = @UniqueConstraint(name = "uk_calendar_map_grant_viewer_share", columnNames = {"viewer_id", "share_id"}))
@Getter @Setter @NoArgsConstructor
public class CalendarMapGrant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "viewer_id", nullable = false) private AppUser viewer;
    // The source share can be deleted independently; every read resolves it again.
    @Column(name = "share_id", nullable = false) private Long shareId;
    @Column(name = "share_owner_id", nullable = false) private Long shareOwnerId;
    @Column(nullable = false) private boolean selected = true;
}
