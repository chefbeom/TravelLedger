package com.playdata.calen.ledger.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.ledger.application.port.in.SynchronizeLedgerEmbeddingsUseCase.Execution;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.domain.model.LedgerEmbeddingSyncData.*;
import java.util.*;
import org.junit.jupiter.api.*;

class SynchronizeLedgerEmbeddingsServiceTest {
    final LedgerEmbeddingJobsPort jobs=mock(LedgerEmbeddingJobsPort.class);
    final LedgerEmbeddingIndexPort index=mock(LedgerEmbeddingIndexPort.class);
    final Execution execution=mock(Execution.class);
    final SynchronizeLedgerEmbeddingsService service=new SynchronizeLedgerEmbeddingsService(jobs,index);
    @BeforeEach void ready() {
        when(execution.enabled()).thenReturn(true); when(execution.leaseValid()).thenReturn(true);
        when(jobs.isCurrent(any())).thenReturn(true);
    }
    @Test void preparesAllClaimsThenSendsUpsertsBeforeDeletesAndAcknowledgesOnlyAfterEachCall() {
        var a=claim(1);var b=claim(2);var c=claim(3);
        when(jobs.claimBatch(4)).thenReturn(List.of(a,b,c));
        when(jobs.prepareLatest(a)).thenReturn(new Prepared(a,document(1)));
        when(jobs.prepareLatest(b)).thenReturn(new Prepared(b,null));
        when(jobs.prepareLatest(c)).thenReturn(new Prepared(c,document(3)));
        service.run(4,execution);
        var order=inOrder(jobs,index,execution);
        order.verify(jobs).recoverExpired(); order.verify(jobs).claimBatch(4);
        order.verify(jobs).prepareLatest(a);order.verify(jobs).prepareLatest(b);order.verify(jobs).prepareLatest(c);
        order.verify(jobs).isCurrent(a);order.verify(jobs).isCurrent(c);order.verify(execution).assertNetworkReady();
        order.verify(index).upsert(List.of(document(1),document(3)));order.verify(jobs).succeeded(a);order.verify(jobs).succeeded(c);
        order.verify(jobs).isCurrent(b);order.verify(execution).assertNetworkReady();order.verify(index).delete(List.of(b.key()));
        order.verify(jobs).succeeded(b);verify(jobs,never()).failed(any(),anyBoolean(),anyString());
    }
    @Test void missingPreparedAndSupersededClaimsAreNotSentOrAcknowledged() {
        var a=claim(1);var b=claim(2);when(jobs.claimBatch(4)).thenReturn(List.of(a,b));
        when(jobs.prepareLatest(a)).thenReturn(null);when(jobs.prepareLatest(b)).thenReturn(new Prepared(b,document(2)));
        when(jobs.isCurrent(b)).thenReturn(false);
        service.run(4,execution);verify(jobs).releaseSuperseded(b);verifyNoInteractions(index);
        verify(jobs,never()).succeeded(any());verify(execution,never()).assertNetworkReady();
    }
    @Test void sourceErrorsKeepTheirExistingClassificationAndDoNotAbortOtherClaims() {
        var a=claim(1);var b=claim(2);var c=claim(3);when(jobs.claimBatch(4)).thenReturn(List.of(a,b,c));
        when(jobs.prepareLatest(a)).thenThrow(new IllegalArgumentException("private source"));
        when(jobs.prepareLatest(b)).thenThrow(new IllegalStateException("SQL details"));
        when(jobs.prepareLatest(c)).thenReturn(new Prepared(c,document(3)));
        service.run(4,execution);
        verify(jobs).failed(a,false,"INVALID_SOURCE");verify(jobs).failed(b,true,"SOURCE_READ_FAILED");
        verify(index).upsert(List.of(document(3)));verify(jobs).succeeded(c);
    }
    @Test void lostLeaseWhilePreparingStopsBeforeTheNextSourceAndDoesNotSendAccumulatedWork() {
        var a=claim(1);var b=claim(2);when(jobs.claimBatch(4)).thenReturn(List.of(a,b));
        when(jobs.prepareLatest(a)).thenReturn(new Prepared(a,document(1)));
        when(execution.leaseValid()).thenReturn(true,false);
        service.run(4,execution);verify(jobs,never()).prepareLatest(b);verifyNoInteractions(index);
        verify(jobs,never()).succeeded(any());
    }
    @Test void disabledOrLostLeaseBeforeSendDoesNotReleaseOrAcknowledgePendingClaims() {
        var a=prepareOne();
        when(execution.enabled()).thenReturn(false);
        service.run(4,execution);verifyNoInteractions(index);verify(jobs,never()).isCurrent(any());verify(jobs,never()).succeeded(a);
    }
    @Test void leaseLossAfterCurrentCheckPreventsNetworkCall() {
        prepareOne();when(jobs.isCurrent(any())).thenAnswer(call->{when(execution.leaseValid()).thenReturn(false);return true;});
        service.run(4,execution);verifyNoInteractions(index);verify(execution,never()).assertNetworkReady();
    }
    @Test void leaseLossAfterSendPreventsBothAcknowledgementAndFurtherBatches() {
        var a=prepareOne();doAnswer(call->{when(execution.leaseValid()).thenReturn(false);return null;}).when(index).upsert(anyList());
        service.run(4,execution);verify(index).upsert(anyList());verify(jobs,never()).succeeded(a);verify(jobs,never()).failed(any(),anyBoolean(),anyString());
    }
    @Test void providerFailureCodesAreFixedAndRetryabilityIsNotReinterpreted() {
        for(var failure:List.of(new LedgerEmbeddingIndexPort.Failure(null,true),new LedgerEmbeddingIndexPort.Failure(null,false),
                new LedgerEmbeddingIndexPort.Failure(429,true),new LedgerEmbeddingIndexPort.Failure(401,false),
                new LedgerEmbeddingIndexPort.Failure(500,true))) {
            reset(jobs,index);when(jobs.isCurrent(any())).thenReturn(true);var a=prepareOne();
            doThrow(failure).when(index).upsert(anyList());service.run(4,execution);
            String code=failure.httpStatus()==null?(failure.retryable()?"API_UNAVAILABLE":"API_CONTRACT_ERROR"):"HTTP_"+failure.httpStatus();
            verify(jobs).failed(a,failure.retryable(),code);verify(execution).reportFailure(code,1,failure.retryable());
            verify(jobs,never()).succeeded(any());
        }
    }
    @Test void invalidRequestAndUnexpectedFailureKeepDistinctNonSensitiveCodes() {
        var a=prepareOne();doThrow(new IllegalArgumentException("private document")).when(index).upsert(anyList());
        service.run(4,execution);verify(jobs).failed(a,false,"INVALID_DOCUMENT");
        reset(index);doThrow(new IllegalStateException("token=private")).when(index).upsert(anyList());
        service.run(4,execution);verify(jobs).failed(a,true,"API_CALL_FAILED");
    }
    @Test void lostLeaseDuringFailureDoesNotPersistStaleFailureOrLogBatchFailure() {
        prepareOne();doAnswer(call->{when(execution.leaseValid()).thenReturn(false);throw new LedgerEmbeddingIndexPort.Failure(429,true);}).when(index).upsert(anyList());
        service.run(4,execution);verify(jobs,never()).failed(any(),anyBoolean(),anyString());verify(execution,never()).reportFailure(anyString(),anyInt(),anyBoolean());
    }
    @Test void upsertFailureStillAllowsTheIndependentDeleteBatch() {
        var a=claim(1);var b=claim(2);when(jobs.claimBatch(4)).thenReturn(List.of(a,b));
        when(jobs.prepareLatest(a)).thenReturn(new Prepared(a,document(1)));when(jobs.prepareLatest(b)).thenReturn(new Prepared(b,null));
        doThrow(new LedgerEmbeddingIndexPort.Failure(503,true)).when(index).upsert(anyList());
        service.run(4,execution);verify(jobs).failed(a,true,"HTTP_503");verify(index).delete(List.of(b.key()));verify(jobs).succeeded(b);
    }
    @Test void transactionMisusePreconditionIsOutsideApiFailureCatch() {
        var a=prepareOne();doThrow(new IllegalStateException("outside DB transaction required")).when(execution).assertNetworkReady();
        assertThatThrownBy(()->service.run(4,execution)).hasMessage("outside DB transaction required");
        verifyNoInteractions(index);verify(jobs,never()).failed(any(),anyBoolean(),anyString());verify(jobs,never()).succeeded(a);
    }
    @Test void emptyBatchStillRecoversExpiredClaimsButDoesNotCallTheIndex() {
        when(jobs.claimBatch(4)).thenReturn(List.of());service.run(4,execution);verify(jobs).recoverExpired();verifyNoInteractions(index);
    }
    Claim prepareOne() {
        var a=claim(1);when(jobs.claimBatch(4)).thenReturn(List.of(a));when(jobs.prepareLatest(a)).thenReturn(new Prepared(a,document(1)));return a;
    }
    static Claim claim(long id) { return new Claim(new Key(7,"ledger_entry",id),2,"test-claim-"+id,1); }
    static Document document(long id) { return new Document(new UUID(0,id),"synthetic",Map.of("owner_id",7L,"source_id",id)); }
}
