package com.playdata.calen.familyalbum.repository;

import com.playdata.calen.familyalbum.domain.FamilyAlbumItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyAlbumItemRepository extends JpaRepository<FamilyAlbumItem, Long> {

    List<FamilyAlbumItem> findAllByAlbumIdInOrderByDisplayOrderAscIdAsc(Collection<Long> albumIds);
}
