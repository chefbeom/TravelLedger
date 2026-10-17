package com.playdata.calen.recordmap.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.playdata.calen.recordmap.domain.model.RecordMapReadData.*;
import com.playdata.calen.recordmap.repository.*;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class JpaRecordMapQueryAdapterTest {
    @Test void searchEscapesWildcardsAndKeepsOwnerScopePrivacyAndBoundedOrdering() {
        var places = mock(RecordPlaceRepository.class);
        var adapter = new JpaRecordMapQueryAdapter(places, mock(PlaceVisitRepository.class), mock(PlacePhotoRepository.class),
                mock(PlaceCollectionRepository.class), mock(RecordMapShareRepository.class), mock(TravelExpenseRecordRepository.class), mock(TravelMediaAssetRepository.class));
        when(places.findVisible(eq(1L), eq(3L), eq("ALL"), isNull(), isNull(), eq("%book!!!_!%%"), eq(false), any()))
                .thenReturn(new PageImpl<>(List.of()));
        adapter.places(new Scope(1L, 3L, true, false), new Selection("ALL", null, null, "BOOK!_%", new Window(0, 500)), false);
        var page = ArgumentCaptor.forClass(Pageable.class);
        verify(places).findVisible(eq(1L), eq(3L), eq("ALL"), isNull(), isNull(), eq("%book!!!_!%%"), eq(false), page.capture());
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(500);
        assertThat(page.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "updatedAt", "id"));
        assertThat(JpaRecordMapQueryAdapter.searchPattern("")).isNull();
    }
}
