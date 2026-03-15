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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "travel_route_segments")
@Getter
@Setter
@NoArgsConstructor
public class TravelRouteSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private TravelPlan plan;

    @Column(nullable = false)
    private LocalDate routeDate;

    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TravelRouteTransportMode transportMode = TravelRouteTransportMode.WALK;

    @Column(precision = 12, scale = 3)
    private BigDecimal distanceKm = BigDecimal.ZERO;

    private Integer durationMinutes = 0;

    private Integer stepCount;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TravelRouteSourceType sourceType = TravelRouteSourceType.MANUAL;

    @Column(length = 160)
    private String startPlaceName;

    @Column(length = 160)
    private String endPlaceName;

    @Lob
    @Column(nullable = false)
    private String routePathJson;

    @Column(length = 7)
    private String lineColorHex;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TravelRouteLineStyle lineStyle = TravelRouteLineStyle.SOLID;

    @Column(length = 500)
    private String memo;

    @Lob
    private String gpxFilesJson;
}
