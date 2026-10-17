package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.model.TravelPlanData.*;
import com.playdata.calen.travel.domain.policy.TravelPlanPolicy;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WriteTravelPlanServiceTest {
    private final TravelPlanStore plans = mock(TravelPlanStore.class);
    private final TravelPlanAttachmentsPort attachments = mock(TravelPlanAttachmentsPort.class);
    private final TravelSummaryInvalidationPort summaries = mock(TravelSummaryInvalidationPort.class);
    private final TravelClusterRefreshPort clusters = mock(TravelClusterRefreshPort.class);
    private final WriteTravelPlanService service = new WriteTravelPlanService(plans, attachments, summaries, clusters);
    private final Draft draft = new Draft(" Trip ", "Japan", LocalDate.of(2026,10,10), LocalDate.of(2026,10,15), "KRW", 2, null, null, null);

    @Test void creationValidatesOwnerPreparesValueAndInvalidatesBeforeRefresh() {
        var value = TravelPlanPolicy.prepare(draft);
        when(plans.create(1L, value)).thenReturn(2L);
        assertThat(service.create(1L, draft)).isEqualTo(new Summary(2L, value, Metrics.empty()));
        var order = inOrder(plans, summaries, clusters);
        order.verify(plans).requireActiveOwner(1L);
        order.verify(plans).create(1L, value);
        order.verify(summaries).invalidateOwner(1L);
        order.verify(clusters).refreshAfterChange(1L);
        verify(plans, never()).metrics(any(), any());
        verifyNoInteractions(attachments);
    }

    @Test void updateSummarizesAfterApplyingValuesWithoutRemovingAttachments() {
        when(plans.metrics(1L, 2L)).thenReturn(Metrics.empty());
        assertThat(service.update(1L, 2L, draft).value()).isEqualTo(TravelPlanPolicy.prepare(draft));
        var order = inOrder(plans, summaries, clusters);
        order.verify(plans).requireOwnedPlan(1L, 2L);
        order.verify(plans).update(2L, TravelPlanPolicy.prepare(draft));
        order.verify(plans).metrics(1L, 2L);
        order.verify(summaries).invalidateOwner(1L);
        order.verify(clusters).refreshAfterChange(1L);
        verifyNoInteractions(attachments);
    }

    @Test void deletionKeepsOwnershipLinksMediaRoutesRelationsAndRefreshOrder() {
        service.delete(1L, 2L);
        var order = inOrder(plans, attachments, summaries, clusters);
        order.verify(plans).requireOwnedPlan(1L, 2L);
        order.verify(attachments).removePlanLinks(2L);
        order.verify(attachments).removeMedia(1L, 2L);
        order.verify(attachments).removeRoutes(1L, 2L);
        order.verify(plans).deletePlanAndRemainingRelations(2L);
        order.verify(summaries).invalidateOwner(1L);
        order.verify(clusters).refreshAfterChange(1L);
    }

    @Test void nonOwnerCannotReachCleanupOrInvalidation() {
        doThrow(new NotFoundException("not owned")).when(plans).requireOwnedPlan(1L, 2L);
        assertThatThrownBy(() -> service.delete(1L, 2L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.update(1L, 2L, draft)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(attachments, summaries, clusters);
        verify(plans, never()).update(any(), any());
    }

    @Test void invalidDatesCannotPersistOrRefresh() {
        var invalid = new Draft("Trip", null, draft.endDate(), draft.startDate(), "KRW", 1, null, null, null);
        assertThatThrownBy(() -> service.create(1L, invalid)).isInstanceOf(BadRequestException.class);
        verify(plans, never()).create(any(), any());
        verifyNoInteractions(attachments, summaries, clusters);
    }

    @Test void cleanupFailureDoesNotProceedToDeleteRelationsOrRegisterRefresh() {
        doThrow(new IllegalStateException("cleanup failure")).when(attachments).removeRoutes(1L, 2L);
        assertThatThrownBy(() -> service.delete(1L, 2L)).hasMessage("cleanup failure");
        verify(plans, never()).deletePlanAndRemainingRelations(any());
        verifyNoInteractions(summaries, clusters);
    }

    @Test void metricsFailureDoesNotReportSuccessfulChange() {
        when(plans.metrics(1L, 2L)).thenThrow(new IllegalStateException("query failure"));
        assertThatThrownBy(() -> service.update(1L, 2L, draft)).hasMessage("query failure");
        verifyNoInteractions(summaries, clusters);
    }

    @Test void databaseDeletionFailureDoesNotInvalidateOrRefresh() {
        doThrow(new IllegalStateException("delete failure")).when(plans).deletePlanAndRemainingRelations(2L);
        assertThatThrownBy(() -> service.delete(1L, 2L)).hasMessage("delete failure");
        verifyNoInteractions(summaries, clusters);
    }
}
