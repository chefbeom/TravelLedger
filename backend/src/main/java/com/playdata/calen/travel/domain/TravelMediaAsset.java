package com.playdata.calen.travel.domain;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "travel_media_assets")
@Getter
@Setter
@NoArgsConstructor
public class TravelMediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private TravelPlan plan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "record_id", nullable = false)
    private TravelExpenseRecord record;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_id", nullable = false)
    private AppUser uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TravelMediaType mediaType = TravelMediaType.PHOTO;

    @Column(nullable = false, length = 220)
    private String originalFileName;

    @Column(nullable = false, length = 260)
    private String storedFileName;

    @Column(nullable = false, length = 255)
    private String storagePath;

    @Column(nullable = false, length = 120)
    private String contentType;

    @Column(nullable = false)
    private Long fileSize;

    @Column(length = 240)
    private String caption;

    @Column(nullable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();
}
