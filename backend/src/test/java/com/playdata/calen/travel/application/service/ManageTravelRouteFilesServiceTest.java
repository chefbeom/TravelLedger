package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.port.out.TravelRouteUploadPort.Upload;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import com.playdata.calen.travel.domain.model.TravelRouteUploadData.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ManageTravelRouteFilesServiceTest {
    final TravelRouteStore routes=mock(TravelRouteStore.class);
    final TravelRouteUploadPort storage=mock(TravelRouteUploadPort.class);
    final TravelSummaryInvalidationPort summaries=mock(TravelSummaryInvalidationPort.class);
    final ManageTravelRouteFilesService service=new ManageTravelRouteFilesService(routes,storage,summaries);
    @BeforeEach void owned(){when(routes.ownedRoute(7L,20L)).thenReturn(new Access(20L,new Plan(10L,"trip","#ABCDEF")));}
    @Test void directUploadRemovesOldFilesBeforeStoringThenReplacesReadsAndEvicts() {
        var first=mock(Upload.class);var second=mock(Upload.class);var a=file("a");var b=file("b");
        when(storage.store(7L,10L,20L,first)).thenReturn(a);when(storage.store(7L,10L,20L,second)).thenReturn(b);
        service.upload(7L,20L,List.of(first,second));
        var order=inOrder(routes,storage,summaries);
        order.verify(routes).ownedRoute(7L,20L);order.verify(routes).removeFilesAndLinks(20L);
        order.verify(storage).store(7L,10L,20L,first);order.verify(storage).store(7L,10L,20L,second);
        order.verify(routes).replaceFiles(20L,List.of(a,b));order.verify(routes).result(20L);order.verify(summaries).invalidateOwner(7L);
        verifyNoInteractions(first,second);
    }
    @Test void ownershipFailurePrecedesAnyMetadataValidationOrStorageAccess() {
        when(routes.ownedRoute(7L,20L)).thenThrow(new NotFoundException("owner"));
        assertThatThrownBy(()->service.upload(7L,20L,null)).hasMessage("owner");
        assertThatThrownBy(()->service.prepare(7L,20L,null)).hasMessage("owner");
        assertThatThrownBy(()->service.complete(7L,20L,null)).hasMessage("owner");
        assertThatThrownBy(()->service.abort(7L,20L,null)).hasMessage("owner");
        verifyNoInteractions(storage,summaries);
    }
    @Test void emptyOrMissingDirectFilesDoNotRemoveExistingLinks() {
        for(List<Upload> files:Arrays.asList(null,List.<Upload>of())) {
            assertThatThrownBy(()->service.upload(7L,20L,files)).hasMessage("Select at least one GPX file.");
        }
        verify(routes,never()).removeFilesAndLinks(any());verifyNoInteractions(storage,summaries);
    }
    @Test void partialDirectUploadFailureDoesNotInventCompensationOrPersistMetadata() {
        var first=mock(Upload.class);var second=mock(Upload.class);
        when(storage.store(7L,10L,20L,first)).thenReturn(file("a"));
        when(storage.store(7L,10L,20L,second)).thenThrow(new IllegalStateException("synthetic upload failure"));
        assertThatThrownBy(()->service.upload(7L,20L,List.of(first,second))).hasMessage("synthetic upload failure");
        verify(routes).removeFilesAndLinks(20L);verify(routes,never()).replaceFiles(any(),anyList());
        verify(storage,never()).abort(any(),any(),any(),any());verifyNoInteractions(summaries);
    }
    @Test void presignedCompletionValidatesEveryNewObjectBeforeOldCleanup() {
        when(storage.supportsPresigned()).thenReturn(true);var a=completed("a");var b=completed("b");
        when(storage.complete(7L,10L,20L,a)).thenReturn(file("a"));when(storage.complete(7L,10L,20L,b)).thenReturn(file("b"));
        service.complete(7L,20L,List.of(a,b));var order=inOrder(routes,storage,summaries);
        order.verify(routes).ownedRoute(7L,20L);order.verify(storage).supportsPresigned();
        order.verify(storage).complete(7L,10L,20L,a);order.verify(storage).complete(7L,10L,20L,b);
        order.verify(routes).removeFilesAndLinks(20L);order.verify(routes).replaceFiles(20L,List.of(file("a"),file("b")));
        order.verify(routes).result(20L);order.verify(summaries).invalidateOwner(7L);
    }
    @Test void failedCompletionKeepsOldMetadataAndDoesNotRemoveAnyPreviousFiles() {
        when(storage.supportsPresigned()).thenReturn(true);var a=completed("a");var b=completed("b");
        when(storage.complete(7L,10L,20L,a)).thenReturn(file("a"));when(storage.complete(7L,10L,20L,b)).thenThrow(new IllegalArgumentException("synthetic stat"));
        assertThatThrownBy(()->service.complete(7L,20L,List.of(a,b))).hasMessage("synthetic stat");
        verify(routes,never()).removeFilesAndLinks(any());verify(routes,never()).replaceFiles(any(),anyList());verifyNoInteractions(summaries);
    }
    @Test void disabledPresigningFailsAfterOwnershipBeforeStorageOrMutations() {
        assertThatThrownBy(()->service.prepare(7L,20L,List.of())).hasMessage("Travel GPX uploads require presigned object storage.");
        assertThatThrownBy(()->service.complete(7L,20L,List.of())).hasMessage("Travel GPX uploads require presigned object storage.");
        verify(routes,never()).removeFilesAndLinks(any());verify(storage,never()).prepare(any(),any(),any(),any());verifyNoInteractions(summaries);
    }
    @Test void preparationReturnsTheExistingModeAndDoesNotChangeRowsOrCaches() {
        when(storage.supportsPresigned()).thenReturn(true);var candidates=List.of(new Candidate("a.gpx","xml",2));
        var target=new Target("PUT","https://storage.invalid/synthetic","test/a","a","a.gpx","xml",2);
        when(storage.prepare(7L,10L,20L,candidates)).thenReturn(List.of(target));
        assertThat(service.prepare(7L,20L,candidates)).isEqualTo(new Prepared("PRESIGNED",List.of(target)));
        verify(routes,never()).replaceFiles(any(),any());verifyNoInteractions(summaries);
    }
    @Test void abortPreservesTheEmptyKeysAndDoesNotRequirePresignedSupportOrEvict() {
        service.abort(7L,20L,null);verify(storage).abort(7L,10L,20L,null);
        verify(storage,never()).supportsPresigned();verify(routes,never()).removeFilesAndLinks(any());verifyNoInteractions(summaries);
    }
    @Test void responseOrPersistenceFailureDoesNotEvictOrAttemptBlindCleanup() {
        var upload=mock(Upload.class);when(storage.store(any(),any(),any(),any())).thenReturn(file("a"));
        when(routes.result(20L)).thenThrow(new IllegalStateException("synthetic read"));
        assertThatThrownBy(()->service.upload(7L,20L,List.of(upload))).hasMessage("synthetic read");
        verify(routes).replaceFiles(20L,List.of(file("a")));verifyNoInteractions(summaries);
        verify(storage,never()).abort(any(),any(),any(),any());
    }
    static GpxFile file(String suffix){return new GpxFile(suffix+".gpx","test/"+suffix,"application/gpx+xml",2);}
    static Completed completed(String suffix){return new Completed("test/"+suffix,suffix+".gpx","application/gpx+xml",2);}
}
