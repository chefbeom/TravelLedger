package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelMediaType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelMediaAssetRepository extends JpaRepository<TravelMediaAsset, Long> {

    @EntityGraph(attributePaths = {"plan", "record", "uploadedBy"})
    List<TravelMediaAsset> findAllByPlanIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(Long planId, Long ownerId);

    @EntityGraph(attributePaths = {"plan", "record", "uploadedBy"})
    List<TravelMediaAsset> findAllByPlanOwnerIdOrderByUploadedAtDescIdDesc(Long ownerId);

    @Query("""
            select asset.id as id,
                   asset.plan.id as planId,
                   asset.plan.name as planName,
                   asset.record.recordType as recordType,
                   asset.originalFileName as originalFileName,
                   asset.caption as caption,
                   asset.uploadedAt as uploadedAt,
                   asset.record.expenseDate as expenseDate,
                   asset.record.title as title,
                   asset.record.country as country,
                   asset.record.region as region,
                   asset.record.placeName as placeName
            from TravelMediaAsset asset
            where asset.plan.owner.id = :ownerId
              and asset.mediaType = :mediaType
            order by asset.uploadedAt desc, asset.id desc
            """)
    List<PhotoFrameMediaProjection> findPhotoFrameMediaByOwnerId(
            @Param("ownerId") Long ownerId,
            @Param("mediaType") TravelMediaType mediaType
    );

    @EntityGraph(attributePaths = {"plan", "record", "uploadedBy"})
    List<TravelMediaAsset> findAllByPlanOwnerIdAndMediaTypeOrderByUploadedAtDescIdDesc(Long ownerId, TravelMediaType mediaType);

    List<TravelMediaAsset> findAllByPlanPublicSharedTrueAndMediaTypeOrderByUploadedAtDescIdDesc(TravelMediaType mediaType);

    @EntityGraph(attributePaths = {"plan", "record", "uploadedBy"})
    List<TravelMediaAsset> findAllByPlanIdInAndMediaTypeOrderByUploadedAtDescIdDesc(Collection<Long> planIds, TravelMediaType mediaType);

    List<TravelMediaAsset> findAllByRecordIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(Long recordId, Long ownerId);

    @EntityGraph(attributePaths = {"plan", "record", "uploadedBy"})
    List<TravelMediaAsset> findAllByRecordIdInOrderByUploadedAtDescIdDesc(Collection<Long> recordIds);

    @EntityGraph(attributePaths = {"plan", "record", "uploadedBy"})
    List<TravelMediaAsset> findAllByIdIn(Collection<Long> ids);

    @Query("""
            select asset.plan.id as planId,
                   count(asset) as mediaItemCount
            from TravelMediaAsset asset
            where asset.plan.owner.id = :ownerId
            group by asset.plan.id
            """)
    List<PlanMediaAggregate> summarizeByPlanOwnerId(@Param("ownerId") Long ownerId);

    Optional<TravelMediaAsset> findByIdAndPlanOwnerId(Long id, Long ownerId);

    Page<TravelMediaAsset> findAllByIdGreaterThanAndContentTypeStartingWithOrderByIdAsc(Long id, String contentTypePrefix, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TravelMediaAsset asset
               set asset.gpsLatitude = null,
                   asset.gpsLongitude = null,
                   asset.gpsExtractedAt = null
             where asset.plan.owner.id = :ownerId
               and (asset.gpsLatitude is not null
                    or asset.gpsLongitude is not null
                    or asset.gpsExtractedAt is not null)
            """)
    int clearGpsMetadataByPlanOwnerId(@Param("ownerId") Long ownerId);

    void deleteAllByPlanId(Long planId);

    void deleteAllByRecordId(Long recordId);

    interface PhotoFrameMediaProjection {
        Long getId();

        Long getPlanId();

        String getPlanName();

        com.playdata.calen.travel.domain.TravelRecordType getRecordType();

        String getOriginalFileName();

        String getCaption();

        java.time.LocalDateTime getUploadedAt();

        java.time.LocalDate getExpenseDate();

        String getTitle();

        String getCountry();

        String getRegion();

        String getPlaceName();
    }

    interface PlanMediaAggregate {
        Long getPlanId();

        Long getMediaItemCount();
    }
}
