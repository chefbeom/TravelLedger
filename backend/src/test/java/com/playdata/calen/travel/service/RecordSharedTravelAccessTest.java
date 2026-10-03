package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecordSharedTravelAccessTest {
    @Mock TravelPlanRepository plans;
    @Mock TravelMediaAssetRepository media;
    @InjectMocks TravelService service;

    @Test void sharingOneTripDoesNotGrantAccessToAnotherTripsPhotos() {
        TravelPlan sharedPlan = plan(10L), unrelated = plan(20L);
        var asset = asset(unrelated);
        when(plans.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(sharedPlan));
        when(media.findById(30L)).thenReturn(Optional.of(asset));
        assertThatThrownBy(() -> service.getRecordSharedMediaDownload(1L, 10L, 30L)).isInstanceOf(NotFoundException.class);
    }
    @Test void deletedOrNonOwnedTripCannotServePhotos() {
        assertThatThrownBy(() -> service.getRecordSharedMediaDownload(1L, 10L, 30L)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(media);
    }
    @Test void validTripPhotoReturnsMetadataWithoutContactingStorage() {
        TravelPlan plan = plan(10L);
        when(plans.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(plan));
        when(media.findById(30L)).thenReturn(Optional.of(asset(plan)));
        assertThat(service.getRecordSharedMediaDownload(1L, 10L, 30L).storagePath()).isEqualTo("test-only/photo.jpg");
    }
    private TravelPlan plan(Long id) { var plan = new TravelPlan(); plan.setId(id); var owner = new AppUser(); owner.setId(1L); plan.setOwner(owner); return plan; }
    private TravelMediaAsset asset(TravelPlan plan) { var asset = new TravelMediaAsset(); asset.setPlan(plan); asset.setStoragePath("test-only/photo.jpg"); asset.setOriginalFileName("photo.jpg"); asset.setContentType("image/jpeg"); return asset; }
}
