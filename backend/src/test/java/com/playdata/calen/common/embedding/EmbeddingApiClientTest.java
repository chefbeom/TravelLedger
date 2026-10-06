package com.playdata.calen.common.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class EmbeddingApiClientTest {

    private static final String ORIGIN = "http://embedding.example.invalid:8001";
    private static final String KEY = "contract-fixture-key-not-a-real-secret";
    private final ObjectMapper mapper = new ObjectMapper();
    private EmbeddingProperties properties;
    private EmbeddingApiClient client;
    private MockRestServiceServer server;

    @BeforeEach void setUp() {
        properties = new EmbeddingProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(ORIGIN);
        properties.setApiKey(KEY);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EmbeddingApiClient(properties, builder.build());
    }

    @Test void disabledClientDoesNotConstructOrInvokeOutboundClient() {
        EmbeddingProperties disabled = new EmbeddingProperties();
        assertThat(ReflectionTestUtils.getField(new EmbeddingApiClient(disabled), "restClient")).isNull();
        RestClient rest = mock(RestClient.class);
        EmbeddingApiClient disabledClient = new EmbeddingApiClient(disabled, rest);
        assertThatThrownBy(() -> disabledClient.embedDocuments(List.of("synthetic"))).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> disabledClient.embedQuery("synthetic query")).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> disabledClient.upsertDocuments(List.of(document(UUID.randomUUID())))).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> disabledClient.deleteDocuments(List.of(UUID.randomUUID()))).isInstanceOf(ServiceUnavailableException.class);
        verifyNoInteractions(rest);
    }

    @Test void documentsKeepExactTextAndUsePostPathJsonAndApiKey() throws Exception {
        String text = "  원문 synthetic\r\n메모: ";
        properties.setBaseUrl(ORIGIN + "/");
        server.expect(requestTo(ORIGIN + "/v1/embed")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", KEY)).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("texts", List.of(text))), true))
                .andRespond(withSuccess(vectors(1), MediaType.APPLICATION_JSON));
        var response = client.embedDocuments(List.of(text));
        assertThat(response.dimensions()).isEqualTo(1024);
        assertThat(response.vectors()).hasSize(1);
        assertThat(response.vectors().get(0)).hasSize(1024);
        server.verify();
    }

    @Test void queryInstructionIsAddedOnlyToQueries() throws Exception {
        String query = "  합성 식비 질문  ";
        String expected = "Instruct: Given a personal finance question, retrieve relevant ledger transactions and recurring income or expense rules.\nQuery:" + query;
        server.expect(requestTo(ORIGIN + "/v1/embed")).andExpect(header("X-API-Key", KEY))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("texts", List.of(expected))), true))
                .andRespond(withSuccess(vectors(1), MediaType.APPLICATION_JSON));
        assertThat(client.embedQuery(query)).hasSize(1024);
        server.verify();
    }

    @Test void acceptsFourEmbeddingsButRejectsFiveBeforeSending() throws Exception {
        List<String> texts = List.of("one", "two", "three", "four");
        server.expect(requestTo(ORIGIN + "/v1/embed"))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("texts", texts)), true))
                .andRespond(withSuccess(vectors(4), MediaType.APPLICATION_JSON));
        assertThat(client.embedDocuments(texts).vectors()).hasSize(4);
        assertThatThrownBy(() -> client.embedDocuments(List.of("1", "2", "3", "4", "5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.embedQueries(List.of("1", "2", "3", "4", "5")))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test void configuredSmallerBatchIsEnforced() {
        properties.setBatchSize(2);
        assertThatThrownBy(() -> client.embedDocuments(List.of("1", "2", "3"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.upsertDocuments(List.of(document(UUID.randomUUID()), document(UUID.randomUUID()), document(UUID.randomUUID()))))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test void upsertUsesOnlyDocumentIdTextMetadataAndAcceptsFour() throws Exception {
        List<EmbeddingDocument> documents = IntStream.range(0, 4).mapToObj(index -> document(UUID.randomUUID())).toList();
        List<UUID> ids = documents.stream().map(EmbeddingDocument::id).toList();
        server.expect(requestTo(ORIGIN + "/v1/documents/upsert")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", KEY))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("documents", documents)), true))
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("status", "ok", "upserted", 4, "ids", ids)), MediaType.APPLICATION_JSON));
        assertThat(client.upsertDocuments(documents).ids()).containsExactlyElementsOf(ids);
        var five = new ArrayList<>(documents);
        five.add(document(UUID.randomUUID()));
        assertThatThrownBy(() -> client.upsertDocuments(five)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.upsertDocuments(List.of(documents.get(0), documents.get(0))))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test void deleteAcceptsOneHundredRejectsOneHundredAndOneAndCountsDistinctRequests() throws Exception {
        List<UUID> ids = IntStream.range(0, 100).mapToObj(index -> UUID.randomUUID()).toList();
        server.expect(requestTo(ORIGIN + "/v1/documents/delete")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", KEY))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("ids", ids)), true))
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("status", "ok", "requested", 100, "ids", ids)), MediaType.APPLICATION_JSON));
        UUID first = ids.get(0);
        server.expect(requestTo(ORIGIN + "/v1/documents/delete"))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("ids", List.of(first))), true))
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("status", "ok", "requested", 1, "ids", List.of(first))), MediaType.APPLICATION_JSON));
        assertThat(client.deleteDocuments(ids).requested()).isEqualTo(100);
        var tooMany = new ArrayList<>(ids);
        tooMany.add(UUID.randomUUID());
        assertThatThrownBy(() -> client.deleteDocuments(tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThat(client.deleteDocuments(List.of(first, first)).requested()).isEqualTo(1);
        server.verify();
    }

    @Test void rejectsEmptyBlankNullInputsWithoutRequests() {
        assertThatThrownBy(() -> client.embedDocuments(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.embedDocuments(List.of(" "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.embedDocuments(Collections.singletonList(null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.upsertDocuments(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.deleteDocuments(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.deleteDocuments(Collections.singletonList(null))).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"vectors\":[],\"dimensions\":1024}", "{\"vectors\":[[0.1]],\"dimensions\":1024}",
            "{\"vectors\":[[null]],\"dimensions\":1024}", "{\"vectors\":[[0.1]],\"dimensions\":768}", "not-json"})
    void rejectsMalformedMissingOrMismatchingEmbeddingResponse(String response) {
        server.expect(requestTo(ORIGIN + "/v1/embed")).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.embedDocuments(List.of("synthetic"))).isInstanceOf(EmbeddingApiException.class)
                .satisfies(exception -> assertThat(((EmbeddingApiException) exception).isRetryable()).isFalse());
        server.verify();
    }

    @Test void rejectsNonfiniteOrNullVectorElementsAtTheCorrectDimension() {
        for (String element : new String[] {"null", "\"NaN\"", "\"Infinity\""}) {
            server.expect(requestTo(ORIGIN + "/v1/embed")).andRespond(withSuccess(
                    "{\"dimensions\":1024,\"vectors\":[[" + String.join(",", Collections.nCopies(1024, element)) + "]]}", MediaType.APPLICATION_JSON));
        }
        for (int responseIndex = 0; responseIndex < 3; responseIndex++) {
            assertThatThrownBy(() -> client.embedDocuments(List.of("synthetic"))).isInstanceOf(EmbeddingApiException.class);
        }
        server.verify();
    }

    @Test void rejectsUpsertAndDeleteAcknowledgementsWithWrongIdsOrCounts() throws Exception {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(ORIGIN + "/v1/documents/upsert")).andRespond(withSuccess(
                mapper.writeValueAsString(Map.of("status", "ok", "upserted", 1, "ids", List.of(UUID.randomUUID()))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ORIGIN + "/v1/documents/delete")).andRespond(withSuccess(
                mapper.writeValueAsString(Map.of("status", "ok", "requested", 0, "ids", List.of(id))), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.upsertDocuments(List.of(document(id)))).isInstanceOf(EmbeddingApiException.class);
        assertThatThrownBy(() -> client.deleteDocuments(List.of(id))).isInstanceOf(EmbeddingApiException.class);
        server.verify();
    }

    @ParameterizedTest @ValueSource(ints = {302, 400, 401, 403, 408, 422, 429, 500, 503})
    void classifiesHttpFailuresWithoutEchoingSensitiveResponseBodies(int status) {
        server.expect(requestTo(ORIGIN + "/v1/embed")).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON).body("{\"detail\":\"synthetic-sensitive-body\"}"));
        assertThatThrownBy(() -> client.embedDocuments(List.of("synthetic-private-document")))
                .isInstanceOf(EmbeddingApiException.class).satisfies(exception -> {
                    var api = (EmbeddingApiException) exception;
                    assertThat(api.getHttpStatus()).isEqualTo(status);
                    assertThat(api.isRetryable()).isEqualTo(status == 408 || status == 429 || status >= 500);
                    assertThat(api.getMessage()).doesNotContain(KEY, "synthetic-sensitive-body", "synthetic-private-document");
                    assertThat(api.getCause()).isNull();
                });
        server.verify();
    }

    @Test void ioFailureIsRetryableAndDoesNotIncludeRawErrorText() {
        server.expect(requestTo(ORIGIN + "/v1/embed")).andRespond(withException(new IOException("synthetic-sensitive-io-error")));
        assertThatThrownBy(() -> client.embedDocuments(List.of("synthetic"))).isInstanceOf(EmbeddingApiException.class)
                .satisfies(exception -> {
                    assertThat(((EmbeddingApiException) exception).isRetryable()).isTrue();
                    assertThat(exception.getMessage()).doesNotContain("synthetic-sensitive-io-error", KEY);
                    assertThat(exception.getCause()).isNull();
                });
        server.verify();
    }

    private EmbeddingDocument document(UUID id) {
        return new EmbeddingDocument(id, "합성 계약 검증 문서\n메모: ", Map.of("domain", "ledger", "owner_id", 7L,
                "source_id", 123L, "record_type", "ledger_entry"));
    }

    private String vectors(int count) throws Exception {
        return mapper.writeValueAsString(Map.of("vectors", Collections.nCopies(count, Collections.nCopies(1024, 0.125)), "dimensions", 1024));
    }
}
