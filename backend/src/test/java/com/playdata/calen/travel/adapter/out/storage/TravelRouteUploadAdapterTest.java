package com.playdata.calen.travel.adapter.out.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.travel.application.port.out.TravelRouteUploadPort.Upload;
import com.playdata.calen.travel.domain.model.TravelRouteData.GpxFile;
import com.playdata.calen.travel.domain.model.TravelRouteUploadData.*;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

class TravelRouteUploadAdapterTest {
    final TravelMediaStorageService storage=mock(TravelMediaStorageService.class);
    final TravelRouteUploadAdapter adapter=new TravelRouteUploadAdapter(storage);
    @Test void directStoreDoesNotReadInputBeforeLegacyStorageAsksForBytesAndDoesNotCopyThem() throws Exception {
        var input=mock(Upload.class);when(storage.storeRouteGpx(any(),any(),any(),any())).thenReturn(stored());
        assertThat(adapter.store(7L,10L,20L,input)).isEqualTo(new GpxFile("route.gpx","test/route.gpx","xml",3));
        var part=ArgumentCaptor.forClass(MultipartFile.class);verify(storage).storeRouteGpx(eq(7L),eq(10L),eq(20L),part.capture());
        verifyNoInteractions(input);
        var bytes=new byte[]{1,2,3};when(input.bytes()).thenReturn(bytes);when(input.name()).thenReturn("files");
        when(input.fileName()).thenReturn("route.gpx");when(input.contentType()).thenReturn("xml");when(input.size()).thenReturn(3L);
        assertThat(part.getValue().getBytes()).isSameAs(bytes);assertThat(part.getValue().getName()).isEqualTo("files");
        assertThat(part.getValue().getOriginalFilename()).isEqualTo("route.gpx");assertThat(part.getValue().getSize()).isEqualTo(3);
        assertThatThrownBy(()->part.getValue().transferTo(new java.io.File("synthetic-not-created.gpx"))).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void checkedReadFailureRemainsAnIOExceptionForTheExistingStorageValidator() throws Exception {
        var input=mock(Upload.class);when(storage.storeRouteGpx(any(),any(),any(),any())).thenReturn(stored());
        var error=new IOException("synthetic read");when(input.bytes()).thenThrow(error);adapter.store(7L,10L,20L,input);
        var part=ArgumentCaptor.forClass(MultipartFile.class);verify(storage).storeRouteGpx(eq(7L),eq(10L),eq(20L),part.capture());
        assertThat(catchThrowable(()->part.getValue().getBytes())).isSameAs(error);
    }
    @Test void nullInputIsNotTurnedIntoAnApparentlyValidMultipartFile() {
        when(storage.storeRouteGpx(7L,10L,20L,null)).thenReturn(stored());adapter.store(7L,10L,20L,null);
        verify(storage).storeRouteGpx(7L,10L,20L,null);
    }
    @Test void presignAndCompleteKeepOwnerPlanRouteAndEveryWireMetadataField() {
        var candidate=new Candidate(" route.gpx "," xml ",3);
        var old=new TravelMediaStorageService.PresignedRouteGpxUpload("PUT","https://storage.invalid/synthetic","test/key","stored"," route.gpx "," xml ",3);
        when(storage.preparePresignedRouteGpxUploads(7L,10L,20L,List.of(new TravelMediaStorageService.RouteGpxUploadCandidate(" route.gpx "," xml ",3)))).thenReturn(List.of(old));
        var targets=adapter.prepare(7L,10L,20L,List.of(candidate));
        assertThat(targets).containsExactly(new Target(old.method(),old.uploadUrl(),old.objectKey(),old.storedFileName(),old.originalFileName(),old.contentType(),old.fileSize()));
        var proof=new TravelMediaStorageService.CompletedRouteGpxUpload("test/key","route.gpx","xml",3);
        when(storage.completePresignedRouteGpxUpload(7L,10L,20L,proof)).thenReturn(stored());
        assertThat(adapter.complete(7L,10L,20L,new Completed("test/key","route.gpx","xml",3))).isEqualTo(new GpxFile("route.gpx","test/route.gpx","xml",3));
        verify(storage).completePresignedRouteGpxUpload(7L,10L,20L,proof);
    }
    @Test void abortAndSupportStatusAreDelegatedWithoutFilteringKeysOrAddingRetries() {
        when(storage.supportsPresignedUpload()).thenReturn(true);assertThat(adapter.supportsPresigned()).isTrue();
        var keys=Arrays.asList("test/a",null,"test/a");adapter.abort(7L,10L,20L,keys);verify(storage).abortPresignedRouteGpxUploads(7L,10L,20L,keys);
    }
    static TravelMediaStorageService.StoredTravelMedia stored(){return new TravelMediaStorageService.StoredTravelMedia("route.gpx","stored.gpx","test/route.gpx","xml",3);}
}
