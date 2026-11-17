package com.playdata.calen.recordmap;

import static org.mockito.Mockito.*;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.recordmap.domain.PlaceVisit;
import com.playdata.calen.recordmap.repository.PlacePhotoRepository;
import com.playdata.calen.recordmap.repository.PlaceVisitRepository;
import com.playdata.calen.recordmap.repository.RecordPlaceRepository;
import com.playdata.calen.recordmap.service.RecordMapMediaService;
import com.playdata.calen.recordmap.service.RecordMapShareService;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RecordMapMediaRollbackTest {
    @Test void uncertainCommitNeverDeletesAPossiblyCommittedPhoto() {
        var visits = mock(PlaceVisitRepository.class);
        var photos = mock(PlacePhotoRepository.class);
        var storage = mock(TravelMediaStorageService.class);
        var service = new RecordMapMediaService(visits, photos, mock(RecordPlaceRepository.class),
                mock(TravelMediaAssetRepository.class), storage, mock(RecordMapShareService.class), mock(AppUserService.class));
        var file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1});
        when(visits.lockOwned(9L, 7L)).thenReturn(Optional.of(new PlaceVisit()));
        when(storage.store(7L, 0L, 9L, file)).thenReturn(new TravelMediaStorageService.StoredTravelMedia(
                "photo.png", "stored.png", "path/stored.png", "image/png", 1L));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(7L, 9L, file);
            var sync = TransactionSynchronizationManager.getSynchronizations().get(0);
            sync.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
            sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verify(storage, never()).deleteImageWithThumbnailsQuietly(any(), any());
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(storage).deleteImageWithThumbnailsQuietly("path/stored.png", "image/png");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
