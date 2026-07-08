package com.playdata.calen.travel.domain;

import com.playdata.calen.account.domain.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "travel_map_share_links")
@Getter
@Setter
@NoArgsConstructor
public class TravelMapShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false, unique = true, length = 80)
    private String token;

    @Column(length = 160)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String planIdsJson = "[]";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String excludedRecordIdsJson = "[]";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String excludedMediaIdsJson = "[]";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String excludedRouteIdsJson = "[]";

    @Column(nullable = false)
    private Boolean active = true;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
