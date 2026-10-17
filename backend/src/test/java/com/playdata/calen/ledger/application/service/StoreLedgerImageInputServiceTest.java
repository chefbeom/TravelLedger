package com.playdata.calen.ledger.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.domain.model.LedgerImageStorageData.StoredImage;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class StoreLedgerImageInputServiceTest {
    private final LedgerImageStoragePort storage=mock(LedgerImageStoragePort.class);
    private final LedgerImageInputReferencePort references=mock(LedgerImageInputReferencePort.class);
    private final LedgerImageStoragePort.Upload upload=mock(LedgerImageStoragePort.Upload.class);
    private final StoreLedgerImageInputService service=new StoreLedgerImageInputService(storage,references);
    private final StoredImage image=new StoredImage("fixture/input",LocalDateTime.of(2026,10,16,12,0));

    @Test void unavailableStorageSelectsTheExistingSpoolFallbackWithoutReadingAnything() {
        assertThat(service.store(7L,11L,upload)).isFalse();
        verify(storage).supportsStorage(); verifyNoMoreInteractions(storage); verifyNoInteractions(references,upload);
    }
    @Test void uploadPrecedesOwnerScopedReferenceAttachmentWithoutAStatusTransition() {
        when(storage.supportsStorage()).thenReturn(true); when(storage.store(7L,11L,upload)).thenReturn(image);
        when(references.attach(7L,11L,image)).thenReturn(1);
        assertThat(service.store(7L,11L,upload)).isTrue();
        var order=inOrder(storage,references); order.verify(storage).supportsStorage(); order.verify(storage).store(7L,11L,upload);
        order.verify(references).attach(7L,11L,image); verifyNoMoreInteractions(storage,references); verifyNoInteractions(upload);
    }
    @Test void definitivelyMissingOwnerOrJobCleansOnlyTheNewlyUploadedObject() {
        when(storage.supportsStorage()).thenReturn(true); when(storage.store(7L,11L,upload)).thenReturn(image);
        assertThatThrownBy(() -> service.store(7L,11L,upload)).isInstanceOf(NotFoundException.class).hasMessage("이미지 분석 기록을 찾을 수 없습니다.");
        var order=inOrder(storage,references); order.verify(storage).store(7L,11L,upload); order.verify(references).attach(7L,11L,image);
        order.verify(storage).delete("fixture/input"); verifyNoMoreInteractions(references);
    }
    @Test void uploadFailureNeverAttemptsAttachmentOrDeletion() {
        var failure=new IllegalStateException("fixture storage failure"); when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(7L,11L,upload)).thenThrow(failure);
        assertThatThrownBy(() -> service.store(7L,11L,upload)).isSameAs(failure);
        verifyNoInteractions(references); verify(storage,never()).delete(any());
    }
    @Test void ambiguousReferenceFailureIsNotRetriedOrBlindlyCompensated() {
        var failure=new IllegalStateException("fixture reference acknowledgement failure"); when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(7L,11L,upload)).thenReturn(image); when(references.attach(7L,11L,image)).thenThrow(failure);
        assertThatThrownBy(() -> service.store(7L,11L,upload)).isSameAs(failure);
        verify(references).attach(7L,11L,image); verify(storage,never()).delete(any());
    }
    @Test void cleanupFailureRetainsThePreviousPropagatedFailure() {
        var failure=new IllegalStateException("fixture cleanup failure"); when(storage.supportsStorage()).thenReturn(true);
        when(storage.store(7L,11L,upload)).thenReturn(image); doThrow(failure).when(storage).delete("fixture/input");
        assertThatThrownBy(() -> service.store(7L,11L,upload)).isSameAs(failure);
        verify(references).attach(7L,11L,image); verify(storage).delete("fixture/input");
    }
}
