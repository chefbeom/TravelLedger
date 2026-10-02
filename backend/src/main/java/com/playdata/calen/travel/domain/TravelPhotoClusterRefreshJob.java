package com.playdata.calen.travel.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "travel_photo_cluster_refresh_jobs", indexes = @Index(name = "idx_cluster_refresh_dirty_owner", columnList = "dirty, owner_id"))
public class TravelPhotoClusterRefreshJob {
    @Id @Column(name = "owner_id") private Long ownerId;
    @Column(nullable = false) private long generation;
    @Column(name = "processed_generation", nullable = false) private long processedGeneration;
    @Column(nullable = false) private boolean dirty;
    protected TravelPhotoClusterRefreshJob() { }
}
