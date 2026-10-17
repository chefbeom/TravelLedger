package com.playdata.calen.travel.service;

import static org.mockito.Mockito.*;

import com.playdata.calen.travel.domain.TravelPhotoCluster;
import com.playdata.calen.travel.domain.TravelPhotoClusterMember;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import com.playdata.calen.travel.adapter.out.persistence.JpaTravelClusterProjectionAdapter;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.repository.TravelPhotoClusterRepository;
import com.playdata.calen.travel.repository.TravelPhotoClusterMemberRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TravelClusterPersistenceTest {

    @Test
    void refreshRequestsInSameTransactionAreMergedAndDispatchAfterCommit() {
        var coordinator = mock(TravelClusterRefreshCoordinator.class);
        @SuppressWarnings("unchecked")
        var providers = (org.springframework.beans.factory.ObjectProvider<TravelClusterRefreshCoordinator>) mock(org.springframework.beans.factory.ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(coordinator);
        var effects = new com.playdata.calen.ledger.adapter.out.travel.TravelBridgeEffects(
                mock(com.playdata.calen.travel.application.port.out.TravelSummaryInvalidationPort.class), providers,
                mock(com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase.class));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            effects.travelChanged(7L);
            effects.travelChanged(7L);
            verify(coordinator, times(1)).markDirtyInCurrentTransaction(7L);
            verify(coordinator, never()).dispatch(any());
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(coordinator).dispatch(7L);
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test
    void unchangedClusterProducesNoWritesAndMetadataChangeKeepsMemberships() {
        TravelPhotoClusterRepository clusters = mock(TravelPhotoClusterRepository.class);
        TravelPhotoClusterMemberRepository members = mock(TravelPhotoClusterMemberRepository.class);
        var adapter = new JpaTravelClusterProjectionAdapter(mock(TravelMediaAssetRepository.class), clusters, members);
        var point = new TravelPhotoClusterData.PhotoPoint(1L, 1L, 1L, "plan", "#3182F6",
                LocalDate.of(2026, 1, 1), LocalTime.NOON, "spot", "title", "KR", "Seoul", "place",
                new BigDecimal("37.5"), new BigDecimal("127"), LocalDateTime.of(2026, 1, 1, 12, 0), false);
        var next = new TravelPhotoClusterPolicy().cluster(List.of(point)).get(0);
        TravelPhotoCluster stored = ReflectionTestUtils.invokeMethod(adapter, "entity", 7L, next);
        TravelPhotoClusterMember member = new TravelPhotoClusterMember();
        member.setOwnerId(7L); member.setClusterId(next.id()); member.setMediaId(1L); member.setSortOrder(0);
        when(clusters.findAllByOwnerIdOrderByMemoryDateDescMemoryTimeDescIdDesc(7L)).thenReturn(List.of(stored));
        when(members.findAllByOwnerIdOrderByClusterIdAscSortOrderAsc(7L)).thenReturn(List.of(member));

        adapter.save(7L, List.of(next));
        verify(clusters, never()).saveAll(any());
        verify(members, never()).saveAll(any());
        stored.setTitle("outdated");
        adapter.save(7L, List.of(next));
        verify(clusters).saveAll(List.of(stored));
        verify(members, never()).deleteByOwnerIdAndClusterIds(any(), any());
        verify(members, never()).saveAll(any());

        adapter.save(7L, List.of());
        verify(members).deleteByOwnerIdAndClusterIds(7L, List.of(next.id()));
        verify(clusters).deleteByOwnerIdAndClusterIds(7L, List.of(next.id()));
        verify(clusters, never()).deleteAllByOwnerId(any());
    }
}
