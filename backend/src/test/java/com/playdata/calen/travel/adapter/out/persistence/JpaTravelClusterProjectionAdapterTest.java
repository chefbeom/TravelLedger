package com.playdata.calen.travel.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.travel.domain.TravelPhotoCluster;
import com.playdata.calen.travel.domain.TravelPhotoClusterMember;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.*;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import com.playdata.calen.travel.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class JpaTravelClusterProjectionAdapterTest {
    private final TravelPhotoClusterRepository clusters = mock(TravelPhotoClusterRepository.class);
    private final TravelPhotoClusterMemberRepository members = mock(TravelPhotoClusterMemberRepository.class);
    private final JpaTravelClusterProjectionAdapter adapter = new JpaTravelClusterProjectionAdapter(mock(TravelMediaAssetRepository.class), clusters, members);

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void removedProjectionUsesOwnerScopedChunksWithoutWholeTableRebuild() {
        var stored = new ArrayList<TravelPhotoCluster>();
        for (long id = 1; id <= 520; id++) { var c = new TravelPhotoCluster(); c.setId(id); c.setOwnerId(7L); stored.add(c); }
        when(clusters.findAllByOwnerIdOrderByMemoryDateDescMemoryTimeDescIdDesc(7L)).thenReturn(stored);
        adapter.save(7L, null);
        ArgumentCaptor<Collection<Long>> clusterIds = ArgumentCaptor.forClass((Class) Collection.class);
        ArgumentCaptor<Collection<Long>> memberClusterIds = ArgumentCaptor.forClass((Class) Collection.class);
        verify(clusters, times(3)).deleteByOwnerIdAndClusterIds(eq(7L), clusterIds.capture());
        verify(members, times(3)).deleteByOwnerIdAndClusterIds(eq(7L), memberClusterIds.capture());
        assertThat(clusterIds.getAllValues()).extracting(Collection::size).containsExactly(256,256,8);
        assertThat(memberClusterIds.getAllValues()).isEqualTo(clusterIds.getAllValues());
        assertThat(clusterIds.getAllValues().stream().flatMap(Collection::stream).toList()).containsExactlyInAnyOrderElementsOf(stored.stream().map(TravelPhotoCluster::getId).toList());
        verify(clusters, never()).deleteAllByOwnerId(any()); verify(members, never()).deleteAllByOwnerId(any());
        verify(clusters, never()).saveAll(any()); verify(members, never()).saveAll(any());
    }
    @Test void decimalScaleAloneDoesNotDirtyAClusterOrItsMemberships() {
        var point = point(1L); var next = new TravelPhotoClusterPolicy().cluster(List.of(point)).get(0);
        TravelPhotoCluster stored = ReflectionTestUtils.invokeMethod(adapter, "entity", 7L, next);
        stored.setLatitude(new BigDecimal("37.5000000")); stored.setLongitude(new BigDecimal("127.0000000")); stored.setMaxDistanceMeters(new BigDecimal("0.00000"));
        var member = new TravelPhotoClusterMember(); member.setClusterId(1L); member.setMediaId(1L);
        when(clusters.findAllByOwnerIdOrderByMemoryDateDescMemoryTimeDescIdDesc(7L)).thenReturn(List.of(stored));
        when(members.findAllByOwnerIdOrderByClusterIdAscSortOrderAsc(7L)).thenReturn(List.of(member));
        adapter.save(7L, List.of(next));
        verify(clusters, never()).saveAll(any()); verify(members, never()).saveAll(any());
        verify(members, never()).deleteByOwnerIdAndClusterIds(any(), any());
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void changedMembershipReplacesOnlyTheAffectedClusterInItsCalculatedOrder() {
        var p1 = point(1L); var p2 = point(2L);
        var next = new TravelPhotoClusterPolicy().cluster(List.of(p1,p2)).get(0);
        var old = new TravelPhotoClusterMember(); old.setClusterId(next.id()); old.setMediaId(1L);
        when(members.findAllByOwnerIdOrderByClusterIdAscSortOrderAsc(7L)).thenReturn(List.of(old));
        adapter.save(7L, List.of(next));
        verify(members).deleteByOwnerIdAndClusterIds(7L, List.of(next.id()));
        ArgumentCaptor<Iterable<TravelPhotoClusterMember>> saved = ArgumentCaptor.forClass((Class) Iterable.class);
        verify(members).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(TravelPhotoClusterMember::getMediaId).containsExactly(2L,1L);
        assertThat(saved.getValue()).extracting(TravelPhotoClusterMember::getSortOrder).containsExactly(0,1);
        assertThat(saved.getValue()).allMatch(member -> member.getOwnerId().equals(7L) && member.getClusterId().equals(next.id()));
        verify(clusters, never()).deleteByOwnerIdAndClusterIds(any(), any());
    }
    private PhotoPoint point(long id) {
        return new PhotoPoint(id, id, 3L, "Trip", "#abcdef", LocalDate.of(2026,10,15), LocalTime.NOON, "Spot", "Place", "KR", "Seoul", "Cafe",
                new BigDecimal("37.5"), new BigDecimal("127"), LocalDateTime.of(2026,10,15,12,0), id == 2L);
    }
}
