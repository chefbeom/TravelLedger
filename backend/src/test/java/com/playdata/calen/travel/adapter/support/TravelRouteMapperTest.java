package com.playdata.calen.travel.adapter.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.travel.application.port.out.TravelRoutePayloadPort;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import com.playdata.calen.travel.dto.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class TravelRouteMapperTest {
    final TravelRoutePayloadPort payload=mock(TravelRoutePayloadPort.class);
    @Test void mapAndFullDetailsPreserveTheirDistinctNullDefaultsColorAndFileNameFiltering() {
        var route=route();
        when(payload.files("metadata")).thenReturn(List.of(new GpxFile(null,"a","xml",1),new GpxFile("","b","xml",1),new GpxFile("named","c","xml",1)));
        var full=TravelRouteMapper.result(route,payload,false);var map=TravelRouteMapper.result(route,payload,true);
        assertThat(full.durationMinutes()).isZero();assertThat(map.durationMinutes()).isNull();
        assertThat(full.lineColorHex()).isEqualTo("#ABCDEF");assertThat(map.lineColorHex()).isEqualTo("#3182F6");
        assertThat(full.gpxFileNames()).containsExactly("named");assertThat(map.gpxFileNames()).containsExactly(null,"","named");
        assertThat(full.distanceKm()).isEqualTo(new BigDecimal("0.000"));assertThat(full.lineStyle()).isEqualTo(TravelRouteLineStyle.SOLID);
        verify(payload).resolve(TravelRouteSourceType.GPX,"metadata","path");verify(payload).decode("path");
    }
    @Test void responseRetainsEveryFieldAndThePreviousJsonRecordShape() throws Exception {
        var result=new Result(20L,new Plan(10L,"trip","#ABCDEF"),null,"title",TravelRouteTransportMode.WALK,new BigDecimal("1.230"),
                5,6,TravelRouteSourceType.MANUAL,"start","end","#123456",TravelRouteLineStyle.SOLID,List.of("route.gpx"),"memo",
                List.of(new Point(new BigDecimal("1.0000000"),new BigDecimal("2.0000000"),"MEMORY",9L,"label")));
        var expected=new TravelRouteSegmentResponse(20L,10L,"trip","#ABCDEF",null,"title",TravelRouteTransportMode.WALK,new BigDecimal("1.230"),
                5,6,TravelRouteSourceType.MANUAL,"start","end","#123456",TravelRouteLineStyle.SOLID,List.of("route.gpx"),"memo",
                List.of(new TravelRoutePointResponse(new BigDecimal("1.0000000"),new BigDecimal("2.0000000"),"MEMORY",9L,"label")));
        assertThat(new ObjectMapper().writeValueAsBytes(TravelRouteMapper.response(result))).isEqualTo(new ObjectMapper().writeValueAsBytes(expected));
    }
    @Test void requestMappingDoesNotPrevalidateCoordinatesOrDropNullPointsBeforeOwnershipCheck() {
        var request=new TravelRouteSegmentRequest(null,"title",null,null,null,null,null,null,null,null,null,null,Arrays.asList(null,new TravelRoutePointRequest(BigDecimal.TEN,BigDecimal.ONE)));
        var draft=TravelRouteMapper.draft(request);assertThat(draft.points()).hasSize(2);assertThat(draft.points().get(0)).isNull();
    }
    static TravelRouteSegment route() {
        var plan=new TravelPlan();plan.setId(10L);plan.setName("trip");plan.setColorHex("#abcdef");
        var route=new TravelRouteSegment();route.setId(20L);route.setPlan(plan);route.setDistanceKm(null);route.setDurationMinutes(null);
        route.setSourceType(TravelRouteSourceType.GPX);route.setRoutePathJson("path");route.setGpxFilesJson("metadata");route.setLineStyle(null);return route;
    }
}
