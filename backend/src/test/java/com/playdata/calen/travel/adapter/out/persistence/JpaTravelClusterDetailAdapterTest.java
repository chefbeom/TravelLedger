package com.playdata.calen.travel.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;

class JpaTravelClusterDetailAdapterTest {
    final AppUserService users=mock(AppUserService.class);
    final TravelPhotoClusterRepository clusters=mock(TravelPhotoClusterRepository.class);
    final TravelPhotoClusterMemberRepository members=mock(TravelPhotoClusterMemberRepository.class);
    final TravelMediaAssetRepository media=mock(TravelMediaAssetRepository.class);
    final JpaTravelClusterDetailAdapter adapter=new JpaTravelClusterDetailAdapter(users,clusters,members,media);
    @Test void missingPageHasTheSameNotFoundAndDoesNotQueryPhotos() {
        when(clusters.findByIdAndOwnerId(10L,7L)).thenReturn(Optional.empty());
        assertThatThrownBy(()->adapter.page(7L,10L,0,12,null)).isInstanceOf(NotFoundException.class).hasMessage("Photo cluster not found.");
        verifyNoInteractions(media,members);
    }
    @Test void pageKeepsSqlPageCountSeparateFromNullableStoredHeaderCountAndOwnedRepresentative() {
        var cluster=cluster(); cluster.setPhotoCount(null); cluster.setMemoryCount(null); cluster.setRepresentativeOverride(null);
        when(clusters.findByIdAndOwnerId(10L,7L)).thenReturn(Optional.of(cluster));
        var asset=photo(11L,7L);
        when(media.findClusterPhotoPage(7L,10L,11L,12L,PageRequest.of(0,1)))
                .thenReturn(new PageImpl<>(List.of(asset),PageRequest.of(0,1),8));
        when(media.findAllByIdIn(List.of(12L))).thenReturn(List.of(photo(12L,8L)));
        var page=adapter.page(7L,10L,0,1,11L);
        assertThat(page.photos()).extracting(p->p.id()).containsExactly(11L); assertThat(page.totalPhotoCount()).isEqualTo(8);
        assertThat(page.hasNext()).isTrue(); assertThat(page.cluster().photoCount()).isZero(); assertThat(page.cluster().memoryCount()).isZero();
        assertThat(page.cluster().representativePhoto()).isNull(); assertThat(page.cluster().representativeMediaId()).isEqualTo(12L);
        assertThat(page.cluster().representativeOverride()).isFalse(); verifyNoInteractions(members);
    }
    @Test void fullDetailKeepsMemberOrderSkipsMissingAndForeignMediaAndFallsBackToOwnedRepresentative() {
        var cluster=cluster(); cluster.setPhotoCount(null); cluster.setMemoryCount(null);
        when(clusters.findByIdAndOwnerId(10L,7L)).thenReturn(Optional.of(cluster));
        when(members.findAllByOwnerIdAndClusterIdInOrderByClusterIdAscSortOrderAsc(7L,List.of(10L)))
                .thenReturn(List.of(member(15L),member(14L),member(99L),member(13L)));
        when(media.findAllByIdIn(Set.of(15L,14L,99L,13L,12L))).thenReturn(List.of(photo(13L,7L),photo(14L,8L),photo(15L,7L),photo(12L,7L)));
        var detail=adapter.detail(7L,10L).orElseThrow();
        assertThat(detail.photos()).extracting(p->p.id()).containsExactly(15L,13L);
        assertThat(detail.photoCount()).isEqualTo(2); assertThat(detail.memoryCount()).isZero();
        assertThat(detail.representativePhoto().id()).isEqualTo(12L);
        assertThat(detail.representativeRecordId()).isEqualTo(22L);
    }
    @Test void representativeFromMembershipKeepsItsOriginalObjectAndStoredCounts() {
        when(clusters.findByIdAndOwnerId(10L,7L)).thenReturn(Optional.of(cluster()));
        when(members.findAllByOwnerIdAndClusterIdInOrderByClusterIdAscSortOrderAsc(7L,List.of(10L))).thenReturn(List.of(member(12L)));
        when(media.findAllByIdIn(Set.of(12L))).thenReturn(List.of(photo(12L,7L)));
        var detail=adapter.detail(7L,10L).orElseThrow();
        assertThat(detail.representativePhoto()).isSameAs(detail.photos().get(0));
        assertThat(detail.photoCount()).isEqualTo(10); assertThat(detail.memoryCount()).isEqualTo(4);
    }
    @Test void flagMutationPreservesInputOrderAndSkipsAssetsDeletedSinceMembershipRead() {
        var first=photo(11L,7L); first.setRepresentativeOverride(true);
        var second=photo(12L,7L); second.setRepresentativeOverride(false);
        when(media.findAllById(List.of(12L,99L,11L))).thenReturn(List.of(first,second));
        adapter.changeRepresentative(List.of(12L,99L,11L),12L);
        assertThat(first.getRepresentativeOverride()).isFalse(); assertThat(second.getRepresentativeOverride()).isTrue();
        verify(media).saveAll(List.of(second,first)); verifyNoInteractions(users,clusters,members);
    }
    @Test void fallbackClusterLookupStaysOwnerScopedAndMissingDetailDoesNotReadMembers() {
        var member=member(12L);
        when(members.findFirstByOwnerIdAndMediaId(7L,12L)).thenReturn(Optional.of(member));
        assertThat(adapter.clusterForMedia(7L,12L)).contains(10L);
        assertThat(adapter.detail(7L,100L)).isEmpty();
        verify(members,never()).findAllByOwnerIdAndClusterIdInOrderByClusterIdAscSortOrderAsc(any(),any());
        verifyNoInteractions(media);
    }
    @Test void ownerValidationDelegatesToTheExistingAccountLookup() {
        adapter.requireOwner(7L); verify(users).getRequiredUser(7L); verifyNoInteractions(clusters,members,media);
    }
    static TravelPhotoCluster cluster() {
        var cluster=new TravelPhotoCluster(); cluster.setId(10L); cluster.setOwnerId(7L); cluster.setRepresentativeMediaId(12L);
        cluster.setRepresentativeRecordId(22L); cluster.setPhotoCount(10); cluster.setMemoryCount(4); return cluster;
    }
    static TravelPhotoClusterMember member(Long id) {
        var member=new TravelPhotoClusterMember(); member.setOwnerId(7L); member.setClusterId(10L); member.setMediaId(id); return member;
    }
    static TravelMediaAsset photo(Long id,Long owner) {
        var user=new AppUser(); user.setId(owner); user.setDisplayName("Owner");
        var plan=new TravelPlan(); plan.setId(1L); plan.setOwner(user);
        var record=new TravelExpenseRecord(); record.setId(22L); record.setPlan(plan);
        var asset=new TravelMediaAsset(); asset.setId(id); asset.setPlan(plan); asset.setRecord(record); asset.setUploadedBy(user);
        return asset;
    }
}
