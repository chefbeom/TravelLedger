package com.playdata.calen.travel.domain.policy;

import static org.assertj.core.api.Assertions.*;
import com.playdata.calen.travel.domain.TravelRouteSourceType;
import com.playdata.calen.travel.domain.model.TravelRouteData.Point;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class TravelRoutePolicyTest {
    @Test void roundsCoordinatesBeforeConsecutiveDeduplicationAndKeepsTheFirstMetadata() {
        var first=new Point(new BigDecimal("37.12345674"),new BigDecimal("127.00000004")," memory ",9L,"  label  ");
        var duplicate=new Point(new BigDecimal("37.12345673"),new BigDecimal("127.00000003"),"ROUTE",null,"ignored");
        var last=new Point(new BigDecimal("37.2"),new BigDecimal("127.1"));
        var result=TravelRoutePolicy.normalizeRoutePointsForStorage(List.of(first,duplicate,last),TravelRouteSourceType.MANUAL);
        assertThat(result).hasSize(2);assertThat(result.get(0).latitude()).isEqualTo(new BigDecimal("37.1234567"));
        assertThat(result.get(0).longitude()).isEqualTo(new BigDecimal("127.0000000"));
        assertThat(result.get(0).pointType()).isEqualTo("MEMORY");assertThat(result.get(0).linkedMemoryId()).isEqualTo(9L);
        assertThat(result.get(0).label()).isEqualTo("label");assertThat(result.get(1).pointType()).isEqualTo("ROUTE");
    }
    @Test void rejectsMissingTooFewOrIndistinguishablePointsUsingExistingMessages() {
        assertThatThrownBy(()->TravelRoutePolicy.normalizeRoutePointsForStorage(null,null)).hasMessage("At least two route points are required.");
        var a=new Point(BigDecimal.ONE,BigDecimal.TEN);
        assertThatThrownBy(()->TravelRoutePolicy.normalizeRoutePointsForStorage(List.of(a),null)).hasMessage("At least two route points are required.");
        assertThatThrownBy(()->TravelRoutePolicy.normalizeRoutePointsForStorage(List.of(a,a),null)).hasMessage("At least two distinct route points are required.");
    }
    @Test void validatesCoordinateBoundsButDoesNotNormalizeNonconsecutiveLoopPointsAway() {
        var a=new Point(new BigDecimal("90"),new BigDecimal("-180"));var b=new Point(BigDecimal.ZERO,BigDecimal.ZERO);
        assertThat(TravelRoutePolicy.normalizeRoutePointsForStorage(List.of(a,b,a),null)).hasSize(3);
        assertThatThrownBy(()->TravelRoutePolicy.normalizeRoutePointsForStorage(List.of(new Point(new BigDecimal("90.1"),BigDecimal.ZERO),b),null))
                .hasMessage("Latitude must be between -90 and 90.");
    }
    @Test void gpxAndManualCapsKeepFirstAndLastPointsAndUseThePreviousCeilingStep() {
        List<Point> points=new ArrayList<>();for(int i=0;i<2000;i++)points.add(new Point(new BigDecimal("37."+String.format("%07d",i)),BigDecimal.ONE));
        var gpx=TravelRoutePolicy.normalizeRoutePointsForStorage(points,TravelRouteSourceType.GPX);
        var manual=TravelRoutePolicy.normalizeRoutePointsForStorage(points,TravelRouteSourceType.MANUAL);
        assertThat(gpx.size()).isLessThanOrEqualTo(900);assertThat(manual.size()).isLessThanOrEqualTo(1800);
        for(var values:List.of(gpx,manual)) {
            assertThat(values.get(0).latitude()).isEqualByComparingTo(points.get(0).latitude());
            assertThat(values.get(values.size()-1).latitude()).isEqualByComparingTo(points.get(points.size()-1).latitude());
        }
        assertThat(TravelRoutePolicy.thinRoutePoints(points,2)).containsExactly(points.get(0),points.get(1999));
    }
    @Test void typesLabelsAndColorFallbackPreserveDefaultsAndLimits() {
        assertThat(TravelRoutePolicy.normalizeRoutePointType("not-a-type")).isEqualTo("ROUTE");
        assertThat(TravelRoutePolicy.normalizeRoutePointType(" MeMoRy ")).isEqualTo("MEMORY");
        assertThat(TravelRoutePolicy.trimRoutePointLabel(" x ".repeat(100))).hasSize(160);
        assertThat(TravelRoutePolicy.trimRoutePointLabel(" ")).isNull();
        assertThat(TravelRoutePolicy.lineColor(" "," #abcdef ")).isEqualTo("#ABCDEF");
        assertThat(TravelRoutePolicy.lineColor(null,null)).isEqualTo("#3182F6");
        assertThatThrownBy(()->TravelRoutePolicy.lineColor("red","#abcdef")).hasMessage("Travel color must use #RRGGBB format.");
    }
}
