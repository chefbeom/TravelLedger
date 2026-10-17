package com.playdata.calen.travel.adapter.out.route;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.travel.domain.TravelRouteSourceType;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import com.playdata.calen.travel.dto.TravelRoutePointRequest;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class TravelRoutePayloadAdapterTest {
    final ObjectMapper json=new ObjectMapper();
    final DataSource data=mock(DataSource.class);
    final TravelMediaStorageService storage=mock(TravelMediaStorageService.class);
    final TravelRoutePayloadAdapter adapter=new TravelRoutePayloadAdapter(json,data,storage);
    @Test void manualSnapshotJsonExactlyMatchesThePreviousRequestRecords() throws Exception {
        var p=new Point(new BigDecimal("37.1234567"),new BigDecimal("-127.0000000"),"MEMORY",9L,"label");
        assertThat(adapter.encode(List.of(p),TravelRouteSourceType.MANUAL))
                .isEqualTo(json.writeValueAsString(List.of(new TravelRoutePointRequest(p.latitude(),p.longitude(),p.pointType(),p.linkedMemoryId(),p.label()))));
        assertThat(adapter.decode(adapter.encode(List.of(p),TravelRouteSourceType.MANUAL))).containsExactly(p);
    }
    @Test void savedArrayDecodeOnlyNormalizesMetadataAndRetainsStoredCoordinateScale() {
        var points=adapter.decode("[{\"latitude\":37.123456789,\"longitude\":127.0,\"pointType\":\" memory \",\"linkedMemoryId\":9,\"label\":\"  old  \"}]");
        assertThat(points.get(0).latitude()).isEqualTo(new BigDecimal("37.123456789"));
        assertThat(points.get(0).pointType()).isEqualTo("MEMORY");assertThat(points.get(0).label()).isEqualTo("old");
    }
    @Test void polyline6RoundTripKeepsExistingEnvelopeAndLossOfPerPointMetadata() throws Exception {
        var points=List.of(p("38.5000000","-120.2000000"),p("40.7000000","-120.9500000"),p("43.2520000","-126.4530000"));
        var encoded=adapter.encode(points,TravelRouteSourceType.GPX);
        assertThat(json.readTree(encoded).get("format").asText()).isEqualTo("POLYLINE6");
        assertThat(adapter.decode(encoded)).extracting(Point::latitude).containsExactly(new BigDecimal("38.5000000"),new BigDecimal("40.7000000"),new BigDecimal("43.2520000"));
        assertThat(adapter.decode(encoded)).allMatch(point->point.pointType().equals("ROUTE")&&point.linkedMemoryId()==null&&point.label()==null);
        assertThat(adapter.encode(List.of(p("0","0"),p("1","1")),TravelRouteSourceType.GPX)).isEqualTo("{\"format\":\"POLYLINE6\",\"encodedPath\":\"??_c`|@_c`|@\"}");
    }
    @Test void emptyWrongAndMalformedSnapshotsRetainTheirPreviousContracts() {
        for(String raw:Arrays.asList(null," ","null","{\"format\":\"POLYLINE6\"}"))assertThat(adapter.decode(raw)).isEmpty();
        assertThatThrownBy(()->adapter.decode("broken")).hasMessage("Failed to read saved route points.");
        assertThatThrownBy(()->adapter.decode("{\"format\":\"other\",\"encodedPath\":\"??\"}")).hasMessage("Failed to read saved route points.");
    }
    @Test void gpxFileMetadataKeepsFieldOrderNullFilteringAndQuietMalformedFallback() {
        assertThat(adapter.encodeFiles(List.of(new GpxFile("a.gpx","test/a","application/gpx+xml",2))))
                .isEqualTo("[{\"originalFileName\":\"a.gpx\",\"storagePath\":\"test/a\",\"contentType\":\"application/gpx+xml\",\"fileSize\":2}]");
        assertThat(adapter.encodeFiles(null)).isEqualTo("[]");
        assertThat(adapter.files("[null,{\"storagePath\":\" \"},{\"storagePath\":\"test/a\",\"fileSize\":2}]")).hasSize(1);
        for(String raw:Arrays.asList(null,"","null","broken"))assertThat(adapter.files(raw)).isEmpty();
        adapter.deleteFiles("[{\"storagePath\":\"test/a\",\"fileSize\":2},{\"storagePath\":\"test/b\",\"fileSize\":3}]");
        var order=inOrder(storage);order.verify(storage).deleteQuietly("test/a");order.verify(storage).deleteQuietly("test/b");
    }
    @Test void originalGpxTrackWinsAndConsecutiveFileBoundaryDuplicatesAreRemoved() {
        when(storage.loadAsResource("test/a")).thenReturn(resource("<gpx><trk><trkseg><trkpt lat='37.1' lon='127.1'/><trkpt lat='37.2' lon='127.2'/></trkseg></trk></gpx>"));
        when(storage.loadAsResource("test/b")).thenReturn(resource("<gpx><rte><rtept lat='37.2' lon='127.2'/><rtept lat='37.3' lon='127.3'/></rte></gpx>"));
        String files=adapter.encodeFiles(List.of(new GpxFile("a","test/a","xml",1),new GpxFile("b","test/b","xml",1)));
        var result=adapter.resolve(TravelRouteSourceType.GPX,files,fallback());
        assertThat(result).extracting(Point::latitude).containsExactly(new BigDecimal("37.1000000"),new BigDecimal("37.2000000"),new BigDecimal("37.3000000"));
        assertThat(result).allMatch(p->p.pointType().equals("ROUTE"));
    }
    @Test void invalidMissingOrOutOfRangeOriginalGpxFallsBackToSnapshot() {
        for(String xml:List.of("<gpx>","<gpx><trkpt lat='91' lon='1'/><trkpt lat='1' lon='1'/></gpx>","<gpx><trkpt lat='bad' lon='1'/></gpx>")) {
            when(storage.loadAsResource("test/a")).thenReturn(resource(xml));
            assertThat(adapter.resolve(TravelRouteSourceType.GPX,file(),fallback())).isEqualTo(adapter.decode(fallback()));
        }
        when(storage.loadAsResource("test/a")).thenThrow(new IllegalStateException("synthetic missing object"));
        assertThat(adapter.resolve(TravelRouteSourceType.GPX,file(),fallback())).isEqualTo(adapter.decode(fallback()));
    }
    @Test void doctypeEntityInputIsRejectedAndManualReadsNeverOpenOriginalFiles() {
        when(storage.loadAsResource("test/a")).thenReturn(resource("<!DOCTYPE gpx [<!ENTITY lat '37.1'>]><gpx><trkpt lat='&lat;' lon='127.1'/><trkpt lat='37.2' lon='127.2'/></gpx>"));
        assertThat(adapter.resolve(TravelRouteSourceType.GPX,file(),fallback())).isEqualTo(adapter.decode(fallback()));
        clearInvocations(storage);
        assertThat(adapter.resolve(TravelRouteSourceType.MANUAL,file(),fallback())).isEqualTo(adapter.decode(fallback()));
        verifyNoInteractions(storage);
    }
    @Test void gpxParsingSkipsNonNumericOrMissingCoordinatesAndClosesItsStream() throws Exception {
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        var bytes="<gpx><trkpt lat='bad' lon='1'/><trkpt lon='1'/><rtept lat='37.1' lon='127.1'/><rtept lat='37.2' lon='127.2'/></gpx>".getBytes(StandardCharsets.UTF_8);
        var resource=new ByteArrayResource(bytes){
            @Override public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream(bytes){@Override public void close() throws java.io.IOException {closed.set(true);super.close();}};}
        };
        when(storage.loadAsResource("test/a")).thenReturn(resource);
        assertThat(adapter.resolve(TravelRouteSourceType.GPX,file(),fallback()).get(0).latitude()).isEqualTo(new BigDecimal("37.1000000"));
        assertThat(closed.get()).isTrue();
    }
    @Test void columnTypeAndSizeBudgetsMatchLegacyDefaultsAndAreCached() throws Exception {
        for(var fixture:List.of(new Object[]{"LONGTEXT",Integer.MAX_VALUE,12000},new Object[]{"CLOB",20,12000},
                new Object[]{"TEXT",65535,12000},new Object[]{"VARCHAR",1000,900},new Object[]{"VARCHAR",100,220})) {
            var ds=mock(DataSource.class);var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);var columns=mock(ResultSet.class);
            when(ds.getConnection()).thenReturn(connection);when(connection.getMetaData()).thenReturn(metadata);
            when(metadata.getColumns(any(),any(),any(),any())).thenReturn(columns);when(columns.next()).thenReturn(true);
            when(columns.getString("TYPE_NAME")).thenReturn((String)fixture[0]);when(columns.getInt("COLUMN_SIZE")).thenReturn((Integer)fixture[1]);
            var codec=new TravelRoutePayloadAdapter(json,ds,storage);
            assertThat(codec.characterBudget()).isEqualTo(fixture[2]);assertThat(codec.characterBudget()).isEqualTo(fixture[2]);
            verify(ds,times(1)).getConnection();verify(columns).close();verify(connection).close();
        }
    }
    @Test void metadataUppercaseFallbackAndConnectionFailurePreserveTheDefaultBudget() throws Exception {
        var conn=mock(Connection.class);var md=mock(DatabaseMetaData.class);var lower=mock(ResultSet.class);var upper=mock(ResultSet.class);
        when(data.getConnection()).thenReturn(conn);when(conn.getMetaData()).thenReturn(md);
        when(md.getColumns(any(),any(),eq("travel_route_segments"),eq("route_path_json"))).thenReturn(lower);
        when(md.getColumns(any(),any(),eq("TRAVEL_ROUTE_SEGMENTS"),eq("ROUTE_PATH_JSON"))).thenReturn(upper);
        when(lower.next()).thenReturn(false);when(upper.next()).thenReturn(true);when(upper.getInt("COLUMN_SIZE")).thenReturn(500);
        assertThat(adapter.characterBudget()).isEqualTo(450);verify(lower).close();verify(upper).close();
        var broken=mock(DataSource.class);when(broken.getConnection()).thenThrow(new SQLException("synthetic"));
        var codec=new TravelRoutePayloadAdapter(json,broken,storage);
        assertThat(codec.characterBudget()).isEqualTo(12000);assertThat(codec.characterBudget()).isEqualTo(12000);verify(broken).getConnection();
    }
    @Test void serializationFailuresKeepTheExistingMessages() throws Exception {
        var broken=mock(ObjectMapper.class);when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("synthetic") {});
        var codec=new TravelRoutePayloadAdapter(broken,data,storage);
        assertThatThrownBy(()->codec.encode(List.of(p("1","1")),TravelRouteSourceType.MANUAL)).hasMessage("Failed to save route points.");
        assertThatThrownBy(()->codec.encodeFiles(List.of())).hasMessage("Failed to save GPX file metadata.");
    }
    static Point p(String lat,String lon){return new Point(new BigDecimal(lat),new BigDecimal(lon));}
    static ByteArrayResource resource(String xml){return new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8));}
    String file(){return adapter.encodeFiles(List.of(new GpxFile("a","test/a","xml",1)));}
    String fallback(){return "[{\"latitude\":1,\"longitude\":1},{\"latitude\":2,\"longitude\":2}]";}
}
