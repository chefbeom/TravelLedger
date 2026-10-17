package com.playdata.calen.travel.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Media;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;

class ManageOwnedTravelMediaServiceTest {
    final TravelMediaStore media=mock(TravelMediaStore.class);
    final TravelMediaStoragePort storage=mock(TravelMediaStoragePort.class);
    final OwnedTravelMediaCachePort cache=mock(OwnedTravelMediaCachePort.class);
    final TravelSummaryInvalidationPort summaries=mock(TravelSummaryInvalidationPort.class);
    final TravelClusterRefreshPort clusters=mock(TravelClusterRefreshPort.class);
    final ManageOwnedTravelMediaService service=new ManageOwnedTravelMediaService(media,storage,cache,summaries,clusters);
    @BeforeEach void ready(){when(media.ownedRecord(7L,10L,TravelRecordType.MEMORY)).thenReturn(new Access(10L,1L));}
    @Test void preparationChecksOwnerBeforeStorageSupportAndDoesNotMutateOrEvict() {
        when(storage.supportsPresigned()).thenReturn(true);var files=List.of(new Candidate("a","image/png",1,null));
        when(storage.prepare(7L,1L,10L,files)).thenReturn(List.of());
        assertThat(service.prepare(7L,10L,TravelRecordType.MEMORY,files)).isEqualTo(new Prepared("PRESIGNED",List.of()));
        var order=inOrder(media,storage);order.verify(media).ownedRecord(7L,10L,TravelRecordType.MEMORY);order.verify(storage).supportsPresigned();
        order.verify(storage).prepare(7L,1L,10L,files);verifyNoInteractions(cache,summaries,clusters);
    }
    @Test void missingRecordBlocksPreparationBeforeAnyExternalConfigurationLookup() {
        when(media.ownedRecord(7L,10L,TravelRecordType.MEMORY)).thenThrow(new NotFoundException("owner"));
        assertThatThrownBy(()->service.prepare(7L,10L,TravelRecordType.MEMORY,null)).hasMessage("owner");verifyNoInteractions(storage);
    }
    @Test void completionKeepsLegacySupportFirstOrderAndStopsBeforeUserLookupWhenUnavailable() {
        assertThatThrownBy(()->service.complete(7L,10L,TravelRecordType.MEMORY,null,null,null)).hasMessage("MinIO presigned upload is not available.");
        verifyNoInteractions(media,cache,summaries,clusters);
    }
    @Test void batchSavesBeforeLinkingAndReturnsPhotosThenEvictsAndRefreshes() {
        when(storage.supportsPresigned()).thenReturn(true);var a=completed("a");var b=completed("b");
        var stored=new Stored("a.png","stored.png","test/a","image/png",1);
        when(storage.complete(any(),any(),any(),any())).thenReturn(stored);
        when(media.save(any(),any(),any(),any(),any(),any())).thenReturn(20L,21L);
        when(media.media(List.of(20L,21L))).thenReturn(List.of(photo()));
        var result=service.complete(7L,10L,TravelRecordType.MEMORY,TravelMediaType.RECEIPT,"  memo  ",List.of(a,b));
        assertThat(result).hasSize(1);var order=inOrder(storage,media,summaries,clusters);
        order.verify(storage).supportsPresigned();order.verify(media).requireUser(7L);order.verify(media).ownedRecord(7L,10L,TravelRecordType.MEMORY);
        order.verify(storage).complete(7L,1L,10L,a);order.verify(media).save(7L,10L,TravelMediaType.PHOTO,"memo",stored,new Gps(new BigDecimal("37.1234568"),new BigDecimal("127.0000000")));
        order.verify(storage).complete(7L,1L,10L,b);order.verify(media).save(any(),any(),any(),any(),any(),any());
        order.verify(media).link(List.of(20L,21L));order.verify(media).media(List.of(20L,21L));order.verify(summaries).invalidateOwner(7L);
        order.verify(clusters).refreshAfterChange(7L);verifyNoInteractions(cache);
    }
    @Test void secondStorageFailureDoesNotLinkOrEvictAndDoesNotInventObjectCleanup() {
        when(storage.supportsPresigned()).thenReturn(true);
        when(storage.complete(any(),any(),any(),any())).thenReturn(new Stored("a","a","test/a","image/png",1)).thenThrow(new IllegalStateException("second stat"));
        assertThatThrownBy(()->service.complete(7L,10L,TravelRecordType.MEMORY,TravelMediaType.PHOTO,null,List.of(completed("a"),completed("b"))))
                .hasMessage("second stat");
        verify(media,never()).link(anyList());verify(storage,never()).delete(any(),any());verifyNoInteractions(summaries,clusters,cache);
    }
    @Test void forcedMemoryPhotoRejectsPdfAfterVerificationBeforePersistence() {
        when(storage.supportsPresigned()).thenReturn(true);when(storage.complete(any(),any(),any(),any())).thenReturn(new Stored("a.pdf","a.pdf","test/a","application/pdf",1));
        assertThatThrownBy(()->service.complete(7L,10L,TravelRecordType.MEMORY,TravelMediaType.RECEIPT,null,List.of(completed("a"))))
                .hasMessage("Photos must use JPG, PNG, WEBP, GIF, or BMP files.");
        verify(media,never()).save(any(),any(),any(),any(),any(),any());verifyNoInteractions(summaries,clusters);
    }
    @Test void emptyInternalBatchHasNoCacheOrClusterEffects() {
        when(storage.supportsPresigned()).thenReturn(true);when(media.media(List.of())).thenReturn(List.of());
        assertThat(service.complete(7L,10L,TravelRecordType.MEMORY,TravelMediaType.PHOTO,null,List.of())).isEmpty();
        verifyNoInteractions(summaries,clusters,cache);
    }
    @Test void cacheHitSkipsDatabaseButMissPublishesOnlyOwnedMetadata() {
        var download=new Download("test/a","image/png","a.png");when(cache.get(7L,20L)).thenReturn(download).thenReturn(null);
        assertThat(service.download(7L,20L)).isSameAs(download);verifyNoInteractions(media);
        when(media.download(7L,20L)).thenReturn(download);assertThat(service.download(7L,20L)).isSameAs(download);
        verify(media).download(7L,20L);verify(cache).put(7L,20L,download);
    }
    @Test void missingDownloadNeverCachesAnAbsentOrUnauthorizedValue() {
        when(media.download(7L,20L)).thenThrow(new NotFoundException("missing"));
        assertThatThrownBy(()->service.download(7L,20L)).hasMessage("missing");verify(cache,never()).put(any(),any(),any());
    }
    @Test void deleteRetainsLinkFileRowCacheSummaryClusterOrder() {
        when(media.ownedMedia(7L,20L)).thenReturn(new Owned(20L,new Download("test/a","image/png","a"),true));
        service.delete(7L,20L);var order=inOrder(media,storage,cache,summaries,clusters);
        order.verify(media).ownedMedia(7L,20L);order.verify(media).removeLink(20L);order.verify(storage).delete("test/a","image/png");
        order.verify(media).delete(20L);order.verify(cache).invalidate(7L,20L);order.verify(summaries).invalidateOwner(7L);order.verify(clusters).refreshAfterChange(7L);
    }
    @Test void receiptDeletionDoesNotRefreshPhotoClusters() {
        when(media.ownedMedia(7L,20L)).thenReturn(new Owned(20L,new Download("test/a","application/pdf","a"),false));
        service.delete(7L,20L);verifyNoInteractions(clusters);
    }
    @Test void storageFailurePreventsRowDeletionAndCacheInvalidation() {
        when(media.ownedMedia(7L,20L)).thenReturn(new Owned(20L,new Download("test/a","image/png","a"),true));
        doThrow(new IllegalStateException("storage")).when(storage).delete(any(),any());
        assertThatThrownBy(()->service.delete(7L,20L)).hasMessage("storage");
        verify(media,never()).delete(any());verifyNoInteractions(cache,summaries,clusters);
    }
    static Completed completed(String key){return new Completed("test/"+key,key+".png","image/png",1,null,new BigDecimal("37.12345675"),new BigDecimal("127"));}
    static Media photo(){return new Media(20L,1L,"trip","#ABCDEF",10L,TravelRecordType.MEMORY,TravelMediaType.PHOTO,"a","image/png",1L,null,"owner",null,null,null,"place",null,null,null,null,null,null,null,null,null,null,false);}
}
