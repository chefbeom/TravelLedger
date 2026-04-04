package com.playdata.calen.travel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "travel_photo_clusters")
@Getter
@Setter
@NoArgsConstructor
public class TravelPhotoCluster {

    @Id
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "representative_media_id", nullable = false)
    private Long representativeMediaId;

    @Column(name = "representative_record_id", nullable = false)
    private Long representativeRecordId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "plan_name", nullable = false, length = 120)
    private String planName;

    @Column(name = "plan_color_hex", nullable = false, length = 7)
    private String planColorHex;

    @Column(name = "memory_date")
    private LocalDate memoryDate;

    @Column(name = "memory_time")
    private LocalTime memoryTime;

    @Column(length = 80)
    private String category;

    @Column(length = 200)
    private String title;

    @Column(length = 120)
    private String country;

    @Column(length = 120)
    private String region;

    @Column(name = "place_name", length = 200)
    private String placeName;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "photo_count", nullable = false)
    private Integer photoCount;

    @Column(name = "memory_count", nullable = false)
    private Integer memoryCount;

    @Column(name = "max_distance_meters", nullable = false, precision = 10, scale = 2)
    private BigDecimal maxDistanceMeters;

    @Column(name = "representative_override", nullable = false)
    private Boolean representativeOverride = false;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
