package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelMediaAsset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelMediaAssetRepository extends JpaRepository<TravelMediaAsset, Long> {

    List<TravelMediaAsset> findAllByPlanIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(Long planId, Long ownerId);

    List<TravelMediaAsset> findAllByPlanOwnerIdOrderByUploadedAtDescIdDesc(Long ownerId);

    List<TravelMediaAsset> findAllByRecordIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(Long recordId, Long ownerId);

    List<TravelMediaAsset> findAllByRecordIdInOrderByUploadedAtDescIdDesc(Collection<Long> recordIds);

    Optional<TravelMediaAsset> findByIdAndPlanOwnerId(Long id, Long ownerId);

    Page<TravelMediaAsset> findAllByIdGreaterThanAndContentTypeStartingWithOrderByIdAsc(Long id, String contentTypePrefix, Pageable pageable);

    void deleteAllByPlanId(Long planId);

    void deleteAllByRecordId(Long recordId);
}
