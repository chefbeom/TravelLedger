package com.playdata.calen.travel.adapter.out.cache;

import static org.mockito.Mockito.*;
import com.playdata.calen.travel.adapter.support.TravelClusterSnapshotMapper;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.Snapshot;
import com.playdata.calen.travel.service.TravelMyMapPhotoClusterSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;

class TravelClusterSnapshotCacheAdapterTest {
    private final TravelMyMapPhotoClusterSnapshotService cache = mock(TravelMyMapPhotoClusterSnapshotService.class);
    private final TravelClusterSnapshotCacheAdapter adapter = new TravelClusterSnapshotCacheAdapter(cache);
    @Test void immediateAndCommittedEvictionAreSeparateAndOrdered() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            adapter.invalidateNow(7L); adapter.invalidateAfterCommit(7L);
            verify(cache).delete(7L);
            TransactionSynchronizationUtils.triggerAfterCommit(); verify(cache, times(2)).delete(7L);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void rollbackNeverRunsCommittedEviction() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            adapter.invalidateNow(7L); adapter.invalidateAfterCommit(7L);
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(cache).delete(7L); verifyNoMoreInteractions(cache);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void legacyCacheUsesExistingSnapshotDtoAndTtlService() {
        var snapshot = Snapshot.empty(); adapter.publishLegacy(7L, snapshot);
        verify(cache).save(7L, TravelClusterSnapshotMapper.response(snapshot)); verifyNoMoreInteractions(cache);
    }
}
