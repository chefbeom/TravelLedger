package com.playdata.calen.familyalbum.repository;

import com.playdata.calen.familyalbum.domain.FamilyAlbum;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyAlbumRepository extends JpaRepository<FamilyAlbum, Long> {

    List<FamilyAlbum> findAllByCategoryIdInOrderByCreatedAtDescIdDesc(Collection<Long> categoryIds);
}
