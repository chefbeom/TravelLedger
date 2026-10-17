package com.playdata.calen.ledger.adapter.out.embedding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.common.embedding.EmbeddingDocument;
import com.playdata.calen.ledger.domain.model.LedgerEmbeddingSyncData.*;
import com.playdata.calen.ledger.embedding.LedgerEmbeddingRecordType;
import com.playdata.calen.ledger.embedding.sync.LedgerEmbeddingSyncStore;
import java.util.*;
import org.junit.jupiter.api.Test;

class LedgerEmbeddingJobsAdapterTest {
    final LedgerEmbeddingSyncStore store=mock(LedgerEmbeddingSyncStore.class);
    final LedgerEmbeddingJobsAdapter adapter=new LedgerEmbeddingJobsAdapter(store);
    @Test void claimMappingAndAllCommandsPreserveOwnerTypeRevisionTokenAndAttempts() {
        for(var type:LedgerEmbeddingRecordType.values()) {
            reset(store);var old=new LedgerEmbeddingSyncStore.Claim(7,type,123,8,"synthetic-token",4);
            when(store.claimBatch(4)).thenReturn(List.of(old));adapter.recoverExpired();
            var claim=adapter.claimBatch(4).get(0);
            assertThat(claim).isEqualTo(new Claim(new Key(7,type.metadataValue(),123),8,"synthetic-token",4));
            when(store.isCurrent(old)).thenReturn(true);assertThat(adapter.isCurrent(claim)).isTrue();
            adapter.releaseSuperseded(claim);adapter.succeeded(claim);adapter.failed(claim,true,"HTTP_429");
            verify(store).recoverExpired();verify(store).releaseSuperseded(old);verify(store).succeeded(old);verify(store).failed(old,true,"HTTP_429");
        }
    }
    @Test void lostClaimDeleteAndUpsertStayThreeDistinctPrepareOutcomes() {
        var old=new LedgerEmbeddingSyncStore.Claim(7,LedgerEmbeddingRecordType.LEDGER_ENTRY,123,2,"synthetic-token",1);
        var claim=new Claim(new Key(7,"ledger_entry",123),2,"synthetic-token",1);
        when(store.prepareLatest(old)).thenReturn(null,new LedgerEmbeddingSyncStore.Prepared(old,null),
                new LedgerEmbeddingSyncStore.Prepared(old,new EmbeddingDocument(new UUID(0,1),"synthetic",Map.of("owner_id",7L))));
        assertThat(adapter.prepareLatest(claim)).isNull();
        assertThat(adapter.prepareLatest(claim)).isEqualTo(new Prepared(claim,null));
        assertThat(adapter.prepareLatest(claim)).isEqualTo(new Prepared(claim,new Document(new UUID(0,1),"synthetic",Map.of("owner_id",7L))));
    }
    @Test void unknownRecordTypeIsRejectedBeforeCallingTheStore() {
        assertThatThrownBy(()->adapter.isCurrent(new Claim(new Key(7,"unknown",1),1,"synthetic",1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("unsupported embedding record type");
        verifyNoInteractions(store);
    }
    @Test void storageExceptionsRemainTheSameForTheCoreToClassify() {
        var error=new IllegalStateException("synthetic store failure");when(store.claimBatch(4)).thenThrow(error);
        assertThat(catchThrowable(()->adapter.claimBatch(4))).isSameAs(error);
    }
}
