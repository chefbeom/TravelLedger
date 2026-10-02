package com.playdata.calen.travel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "travel_photo_cluster_members", indexes = {
        @jakarta.persistence.Index(name = "idx_cluster_members_owner_cluster_order", columnList = "owner_id, cluster_id, sort_order"),
        @jakarta.persistence.Index(name = "idx_cluster_members_owner_media", columnList = "owner_id, media_id")})
@Getter
@Setter
@NoArgsConstructor
public class TravelPhotoClusterMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "cluster_id", nullable = false)
    private Long clusterId;

    @Column(name = "media_id", nullable = false)
    private Long mediaId;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
