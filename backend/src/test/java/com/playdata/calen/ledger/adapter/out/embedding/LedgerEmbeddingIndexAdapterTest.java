package com.playdata.calen.ledger.adapter.out.embedding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.embedding.*;
import com.playdata.calen.ledger.application.port.out.LedgerEmbeddingIndexPort;
import com.playdata.calen.ledger.domain.model.LedgerEmbeddingSyncData.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LedgerEmbeddingIndexAdapterTest {
    final EmbeddingApiClient client=mock(EmbeddingApiClient.class);
    final LedgerEmbeddingIndexAdapter adapter=new LedgerEmbeddingIndexAdapter(client);
    @Test void upsertKeepsExactWireJsonFieldOrderMetadataAndDocumentIds() throws Exception {
        var metadata=new LinkedHashMap<String,Object>();metadata.put("owner_id",7L);metadata.put("amount","123.4500");metadata.put("title"," synthetic ");
        var source=new Document(UUID.fromString("478b3f6d-a9a4-5cbc-8399-bd378f740e80"),"line1\nline2",metadata);
        adapter.upsert(List.of(source));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EmbeddingDocument>> request=ArgumentCaptor.forClass(List.class);
        verify(client).upsertDocuments(request.capture());
        var json=new ObjectMapper();
        assertThat(json.writeValueAsBytes(new EmbeddingApiModels.UpsertRequest(request.getValue())))
                .isEqualTo(json.writeValueAsBytes(new EmbeddingApiModels.UpsertRequest(List.of(new EmbeddingDocument(source.id(),source.text(),metadata)))));
    }
    @Test void deleteDerivesTheSameOwnerScopedUuidV5AtTheOutboundBoundary() {
        adapter.delete(List.of(new Key(7,"ledger_entry",123),new Key(7,"recurring_rule",123)));
        verify(client).deleteDocuments(List.of(UUID.fromString("478b3f6d-a9a4-5cbc-8399-bd378f740e80"),UUID.fromString("136b73b8-3e73-5317-bcb3-76e8d75cac5b")));
    }
    @Test void onlyKnownProviderFailuresAreTranslatedWithoutRetainingSensitiveDetails() {
        for(Integer status:Arrays.asList(null,401,429,500)) for(boolean retryable:List.of(false,true)) {
            reset(client);doThrow(new EmbeddingApiException("private response and key",status,retryable)).when(client).upsertDocuments(anyList());
            var error=catchThrowable(()->adapter.upsert(List.of(doc())));
            assertThat(error).isInstanceOf(LedgerEmbeddingIndexPort.Failure.class).hasMessage("Embedding index call failed.").hasNoCause();
            var failure=(LedgerEmbeddingIndexPort.Failure)error;assertThat(failure.httpStatus()).isEqualTo(status);assertThat(failure.retryable()).isEqualTo(retryable);
        }
    }
    @Test void deleteTranslatesProviderFailureButPreservesInvalidSourceAndUnknownFailures() {
        doThrow(new EmbeddingApiException("sensitive",503,true)).when(client).deleteDocuments(anyList());
        assertThatThrownBy(()->adapter.delete(List.of(new Key(7,"ledger_entry",123)))).isInstanceOf(LedgerEmbeddingIndexPort.Failure.class).hasNoCause();
        assertThatThrownBy(()->adapter.delete(List.of(new Key(7,"unsupported",123)))).isInstanceOf(IllegalArgumentException.class);
        reset(client);var unexpected=new IllegalStateException("unavailable");doThrow(unexpected).when(client).upsertDocuments(anyList());
        assertThat(catchThrowable(()->adapter.upsert(List.of(doc())))).isSameAs(unexpected);
    }
    @Test void documentValueKeepsValidationMetadataSnapshotOrderAndRedaction() {
        var map=new LinkedHashMap<String,Object>();map.put("owner_id",7L);map.put("memo","private memo");
        var document=new Document(new UUID(0,1),"private text",map);map.clear();
        assertThat(document.metadata().keySet()).containsExactly("owner_id","memo");
        assertThatThrownBy(()->document.metadata().put("extra",1)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(document.toString()).doesNotContain("private text","private memo").contains("<redacted>");
        assertThatThrownBy(()->new Document(null,"text",Map.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->new Document(new UUID(0,1)," ",Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Document(new UUID(0,1),"text",null)).isInstanceOf(NullPointerException.class);
    }
    static Document doc() { return new Document(new UUID(0,1),"synthetic",Map.of("owner_id",7L)); }
}
