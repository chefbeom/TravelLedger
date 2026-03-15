package com.playdata.calen.familyalbum.repository;

import com.playdata.calen.familyalbum.domain.FamilyMediaAsset;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyMediaAssetRepository extends JpaRepository<FamilyMediaAsset, Long> {

    List<FamilyMediaAsset> findAllByCategoryIdIn(Collection<Long> categoryIds);
}
