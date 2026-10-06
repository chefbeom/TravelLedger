package com.playdata.calen.common.embedding;

import com.playdata.calen.common.embedding.EmbeddingApiModels.DeleteRequest;
import com.playdata.calen.common.embedding.EmbeddingApiModels.DeleteResponse;
import com.playdata.calen.common.embedding.EmbeddingApiModels.EmbedRequest;
import com.playdata.calen.common.embedding.EmbeddingApiModels.EmbedResponse;
import com.playdata.calen.common.embedding.EmbeddingApiModels.UpsertRequest;
import com.playdata.calen.common.embedding.EmbeddingApiModels.UpsertResponse;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;

/** Explicit, single-batch calls only: no automatic indexing, splitting or retries. */
@Component
public class EmbeddingApiClient {

    public static final int EXPECTED_DIMENSIONS = 1024;
    public static final int MAX_DELETE_IDS = 100;
    private static final String QUERY_INSTRUCTION =
            "Given a personal finance question, retrieve relevant ledger transactions and recurring income or expense rules.";

    private final EmbeddingProperties properties;
    private final RestClient restClient;

    @Autowired
    public EmbeddingApiClient(EmbeddingProperties properties) {
        this.properties = Objects.requireNonNull(properties);
        // Disabled instances do not even construct an outbound HTTP client.
        this.restClient = properties.isEnabled() ? createRestClient(properties) : null;
    }

    EmbeddingApiClient(EmbeddingProperties properties, RestClient restClient) {
        this.properties = Objects.requireNonNull(properties);
        this.restClient = Objects.requireNonNull(restClient);
    }

    /** Documents are embedded as-is, with no Qwen query instruction. */
    public EmbedResponse embedDocuments(List<String> texts) {
        ensureEnabled();
        validateTexts(texts);
        return embed(new EmbedRequest(texts));
    }

    /** This is vector generation only, not Qdrant search or an ownership filter. */
    public EmbedResponse embedQueries(List<String> queries) {
        ensureEnabled();
        validateTexts(queries);
        List<String> instructed = queries.stream()
                .map(query -> "Instruct: " + QUERY_INSTRUCTION + "\nQuery:" + query)
                .toList();
        return embed(new EmbedRequest(instructed));
    }

    public List<Double> embedQuery(String query) {
        if (query == null) throw new IllegalArgumentException("query is required");
        return embedQueries(List.of(query)).vectors().get(0);
    }

    public UpsertResponse upsertDocuments(List<EmbeddingDocument> documents) {
        ensureEnabled();
        validateBatch(documents, properties.getBatchSize(), "documents");
        List<UUID> ids = documents.stream().map(EmbeddingDocument::id).toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("a document batch must not contain duplicate IDs");
        }
        UpsertResponse response = post("/v1/documents/upsert", new UpsertRequest(documents), UpsertResponse.class);
        if (response == null || !"ok".equals(response.status()) || response.upserted() == null
                || response.upserted() != ids.size() || !matchingIds(response.ids(), ids)) {
            throw invalidResponse();
        }
        return new UpsertResponse(response.status(), response.upserted(), List.copyOf(response.ids()));
    }

    /** IDs must be derived by the backend from previously authorized source records. */
    public DeleteResponse deleteDocuments(List<UUID> ids) {
        ensureEnabled();
        validateBatch(ids, MAX_DELETE_IDS, "ids");
        List<UUID> distinctIds = List.copyOf(new LinkedHashSet<>(ids));
        DeleteResponse response = post("/v1/documents/delete", new DeleteRequest(distinctIds), DeleteResponse.class);
        if (response == null || !"ok".equals(response.status()) || response.requested() == null
                || response.requested() != distinctIds.size() || !matchingIds(response.ids(), distinctIds)) {
            throw invalidResponse();
        }
        return new DeleteResponse(response.status(), response.requested(), List.copyOf(response.ids()));
    }

    private EmbedResponse embed(EmbedRequest request) {
        EmbedResponse response = post("/v1/embed", request, EmbedResponse.class);
        if (response == null || response.dimensions() == null || response.dimensions() != EXPECTED_DIMENSIONS
                || response.vectors() == null || response.vectors().size() != request.texts().size()) {
            throw invalidResponse();
        }
        for (List<Double> vector : response.vectors()) {
            if (vector == null || vector.size() != EXPECTED_DIMENSIONS
                    || vector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw invalidResponse();
            }
        }
        return new EmbedResponse(response.vectors().stream().map(List::copyOf).toList(), response.dimensions());
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        ensureEnabled();
        String baseUrl = properties.getBaseUrl().strip().replaceAll("/+$", "");
        try {
            return restClient.post()
                    .uri(URI.create(baseUrl + path))
                    .header("X-API-Key", properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(statusCode -> !statusCode.is2xxSuccessful(), (request, response) -> {
                        int status = response.getStatusCode().value();
                        throw new EmbeddingApiException("임베딩 수신 API 요청이 실패했습니다. HTTP " + status,
                                status, status == 408 || status == 429 || status >= 500);
                    })
                    .body(responseType);
        } catch (ResourceAccessException exception) {
            // Raw HTTP errors may contain sensitive payloads. Do not log or attach them.
            throw new EmbeddingApiException("임베딩 수신 API와 통신할 수 없습니다.", null, true);
        } catch (RestClientException exception) {
            throw new EmbeddingApiException("임베딩 수신 API의 응답을 해석할 수 없습니다.", null, false);
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) throw new ServiceUnavailableException("임베딩 연동이 비활성화되어 있습니다.");
        if (restClient == null || !properties.isBaseUrlValid() || !properties.isApiKeyValid()
                || !properties.isTimeoutsValid() || properties.getBatchSize() < 1 || properties.getBatchSize() > 4) {
            throw new ServiceUnavailableException("임베딩 연동 설정이 올바르지 않습니다.");
        }
    }

    private void validateTexts(List<String> texts) {
        validateBatch(texts, properties.getBatchSize(), "texts");
        if (texts.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("texts must not be blank");
    }

    private <T> void validateBatch(List<T> values, int maximum, String label) {
        if (values == null || values.isEmpty() || values.size() > maximum || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(label + " must contain 1.." + maximum + " non-null items");
        }
    }

    private boolean matchingIds(List<UUID> actual, List<UUID> expected) {
        return actual != null && actual.size() == expected.size() && actual.stream().noneMatch(Objects::isNull)
                && new HashSet<>(actual).equals(new HashSet<>(expected));
    }

    private EmbeddingApiException invalidResponse() {
        return new EmbeddingApiException("임베딩 수신 API의 응답 형식이 계약과 일치하지 않습니다.", null, false);
    }

    private static RestClient createRestClient(EmbeddingProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
