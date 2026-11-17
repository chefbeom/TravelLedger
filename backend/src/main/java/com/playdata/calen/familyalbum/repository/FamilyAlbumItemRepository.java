package com.playdata.calen.familyalbum.repository;

import com.playdata.calen.familyalbum.domain.FamilyAlbumItem;
import java.util.Collection;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamilyAlbumItemRepository extends JpaRepository<FamilyAlbumItem, Long> {

    List<FamilyAlbumItem> findAllByAlbumIdInOrderByDisplayOrderAscIdAsc(Collection<Long> albumIds);

    Page<FamilyAlbumItem> findAllByAlbumIdOrderByDisplayOrderAscIdAsc(Long albumId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select item from FamilyAlbumItem item
            join fetch item.media media join fetch item.album album join fetch album.category category
            where media.owner.id = :ownerId order by item.id
            """)
    List<FamilyAlbumItem> lockOwnedMediaReferencesForWithdrawal(@Param("ownerId") Long ownerId);

    @Query("""
            select item.album.id as albumId, count(item) as itemCount
            from FamilyAlbumItem item
            where item.album.id in :albumIds
            group by item.album.id
            """)
    List<AlbumItemCountView> countByAlbumIds(@Param("albumIds") Collection<Long> albumIds);

    interface AlbumItemCountView {
        Long getAlbumId();

        long getItemCount();
    }
}
