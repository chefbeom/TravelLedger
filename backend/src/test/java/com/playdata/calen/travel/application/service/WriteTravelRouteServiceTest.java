package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WriteTravelRouteServiceTest {
    final TravelRouteStore routes=mock(TravelRouteStore.class);
    final TravelRoutePayloadPort payload=mock(TravelRoutePayloadPort.class);
    final TravelSummaryInvalidationPort summaries=mock(TravelSummaryInvalidationPort.class);
    final WriteTravelRouteService service=new WriteTravelRouteService(routes,payload,summaries);
    @BeforeEach void defaults(){
        when(routes.ownedPlan(7L,10L)).thenReturn(new Plan(10L,"Trip","#abcdef"));
        when(routes.ownedRoute(7L,20L)).thenReturn(new Access(20L,new Plan(10L,"Trip","#abcdef")));
        when(routes.create(eq(10L),any())).thenReturn(20L);when(payload.characterBudget()).thenReturn(12000);
        when(payload.encode(anyList(),any())).thenReturn("snapshot");
    }
    @Test void creationNormalizesFieldsAndKeepsOwnershipSaveReadThenEvictionOrder() {
        var response=result();when(routes.result(20L)).thenReturn(response);
        assertThat(service.create(7L,10L,draft(2))).isSameAs(response);
        var value=ArgumentCaptor.forClass(Value.class);
        var order=inOrder(routes,summaries);order.verify(routes).ownedPlan(7L,10L);order.verify(routes).create(eq(10L),value.capture());
        order.verify(routes).result(20L);order.verify(summaries).invalidateOwner(7L);
        assertThat(value.getValue().title()).isEqualTo("route");assertThat(value.getValue().distanceKm()).isEqualTo(new BigDecimal("1.235"));
        assertThat(value.getValue().transportMode()).isEqualTo(TravelRouteTransportMode.WALK);
        assertThat(value.getValue().sourceType()).isEqualTo(TravelRouteSourceType.MANUAL);
        assertThat(value.getValue().lineColorHex()).isEqualTo("#ABCDEF");assertThat(value.getValue().lineStyle()).isEqualTo(TravelRouteLineStyle.SOLID);
        assertThat(value.getValue().startPlaceName()).isEqualTo("start");assertThat(value.getValue().endPlaceName()).isNull();assertThat(value.getValue().memo()).isEqualTo("memo");
    }
    @Test void ownershipFailurePrecedesPointPreparationAndDoesNotAccessFilesOrCaches() {
        when(routes.ownedPlan(7L,10L)).thenThrow(new NotFoundException("owner"));
        assertThatThrownBy(()->service.create(7L,10L,draft(1))).hasMessage("owner");
        verifyNoInteractions(payload,summaries);verify(routes,never()).create(any(),any());
    }
    @Test void updateUsesTheExistingRoutesPlanAndDoesNotCreateOrRemoveAttachments() {
        service.update(7L,20L,draft(2));verify(routes).ownedRoute(7L,20L);verify(routes).update(eq(20L),any());
        verify(routes,never()).create(any(),any());verify(routes,never()).removeFilesAndLinks(any());verify(summaries).invalidateOwner(7L);
    }
    @Test void storedPathBudgetUsesTheSameRepeatedThinningAndFinalSerialization() {
        when(payload.characterBudget()).thenReturn(350);
        when(payload.encode(anyList(),any())).thenAnswer(call->"x".repeat(((List<?>)call.getArgument(0)).size()*100));
        service.create(7L,10L,draft(20));
        var value=ArgumentCaptor.forClass(Value.class);verify(routes).create(eq(10L),value.capture());
        assertThat(value.getValue().path().length()).isLessThanOrEqualTo(350);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Point>> points=ArgumentCaptor.forClass(List.class);
        verify(payload,atLeast(3)).encode(points.capture(),eq(TravelRouteSourceType.MANUAL));
        var captures=points.getAllValues();assertThat(captures.get(captures.size()-1)).isEqualTo(captures.get(captures.size()-2));
        assertThat(captures.get(captures.size()-1).get(0).latitude()).isEqualByComparingTo("1");
    }
    @Test void twoPointSnapshotIsNotSilentlyRejectedWhenStillOverTheLegacyBudget() {
        when(payload.characterBudget()).thenReturn(1);
        service.create(7L,10L,draft(2));
        verify(payload,times(2)).encode(anyList(),eq(TravelRouteSourceType.MANUAL));
        verify(routes).create(eq(10L),any());
    }
    @Test void invalidOrIndistinguishablePointsPreventPersistence() {
        assertThatThrownBy(()->service.create(7L,10L,draft(1))).hasMessage("At least two route points are required.");
        verify(routes,never()).create(any(),any());verifyNoInteractions(payload,summaries);
    }
    @Test void responseReadFailureDoesNotEvictAndMustRollBackAtTheTransactionBoundary() {
        when(routes.result(20L)).thenThrow(new IllegalStateException("read failed"));
        assertThatThrownBy(()->service.create(7L,10L,draft(2))).hasMessage("read failed");verifyNoInteractions(summaries);
    }
    @Test void deleteRetainsOwnerLinksFilesRowAndEvictionOrder() {
        service.delete(7L,20L);var order=inOrder(routes,summaries);
        order.verify(routes).ownedRoute(7L,20L);order.verify(routes).removeFilesAndLinks(20L);
        order.verify(routes).delete(20L);order.verify(summaries).invalidateOwner(7L);verifyNoInteractions(payload);
    }
    @Test void failedFileCleanupDoesNotDeleteTheRowOrEvict() {
        doThrow(new IllegalStateException("file cleanup")).when(routes).removeFilesAndLinks(20L);
        assertThatThrownBy(()->service.delete(7L,20L)).hasMessage("file cleanup");
        verify(routes,never()).delete(any());verifyNoInteractions(summaries);
    }
    static Draft draft(int count) {
        var points=new ArrayList<Point>();for(int i=0;i<count;i++)points.add(new Point(BigDecimal.valueOf(i+1),BigDecimal.ONE));
        return new Draft(LocalDate.of(2026,10,16)," route ",null,new BigDecimal("1.2345"),2,null,null," start "," ",null,null," memo ",points);
    }
    static Result result(){return new Result(20L,new Plan(10L,"Trip","#ABCDEF"),LocalDate.of(2026,10,16),"route",TravelRouteTransportMode.WALK,
            new BigDecimal("1.235"),2,null,TravelRouteSourceType.MANUAL,"start",null,"#ABCDEF",TravelRouteLineStyle.SOLID,List.of(),"memo",List.of());}
}
