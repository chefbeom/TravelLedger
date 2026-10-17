package com.playdata.calen.ledger.adapter.out.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.*;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.ai.*;
import com.playdata.calen.ledger.application.port.out.LedgerImageExtractionPort;
import com.playdata.calen.ledger.domain.model.LedgerImageData.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import org.springframework.web.client.RestClient;

class ImageLedgerExtractionHttpAdapterTest {
    private static final String BASE = "http://localhost:1234";
    private static final String RESPONSE = "{\"ok\":true,\"documentType\":\"RECEIPT\",\"rawText\":\"fixture image\",\"entries\":[],\"warnings\":[]}";
    private final ObjectMapper json = new ObjectMapper();
    private final LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
    private final LedgerAiRequestQueue queue = spy(new LedgerAiRequestQueue());
    private final ImageLedgerExtractionHttpAdapter adapter = spy(new ImageLedgerExtractionHttpAdapter(properties,queue,json,new LedgerImageResponseCodec(json)));
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private MockRestServiceServer server;

    @BeforeEach void mockTransport() {
        properties.setModel("fixture-image-model"); properties.setLmStudioApiKey("fixture-only-token");
        properties.setTemperature(0.8); properties.setMaxTokens(100);
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        doReturn(builder.build()).when(adapter).createClient(any());
        ReflectionTestUtils.setField(adapter,"meterRegistry",metrics);
    }

    private ImageContent image(String contentType) {
        return new ImageContent() {
            public String contentType() { return contentType; }
            public byte[] bytes() { return new byte[]{1,2,3,4}; }
        };
    }

    private RequestMatcher body(Consumer<JsonNode> assertion) {
        return request -> assertion.accept(json.readTree(((MockClientHttpRequest)request).getBodyAsString()));
    }

    private void assertMetric(String status) {
        assertThat(metrics.get("calen.external.workflow.requests").tag("workflow","ledger-ai-image-lmstudio")
                .tag("status",status).counter().count()).isEqualTo(1);
        assertThat(metrics.get("calen.external.workflow.request").tag("workflow","ledger-ai-image-lmstudio")
                .tag("status",status).timer().count()).isEqualTo(1);
    }

    @Test void featureSpecificRequestKeepsSchemaPromptImageAndQueueContract() {
        properties.getImage().setModel("image-only"); properties.getImage().setApiKey("image-fixture-token");
        server.expect(requestTo(BASE+"/v1/chat/completions")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization","Bearer image-fixture-token")).andExpect(body(root -> {
                    assertThat(root.path("model").asText()).isEqualTo("image-only");
                    assertThat(root.path("temperature").asDouble()).isEqualTo(0.05);
                    assertThat(root.path("max_tokens").asInt()).isEqualTo(4096); assertThat(root.path("stream").asBoolean()).isFalse();
                    assertThat(root.at("/response_format/type").asText()).isEqualTo("json_schema");
                    assertThat(root.at("/response_format/json_schema/name").asText()).isEqualTo("ledger_image_analysis_response");
                    assertThat(root.at("/response_format/json_schema/schema/required")).contains(json.valueToTree("entries"));
                    assertThat(root.at("/messages/0/content").asText()).contains("untrusted data","No markdown","same row","foreignAmount");
                    assertThat(root.at("/messages/1/content/0/text").asText()).contains("Image type hint: PAYMENT_CAPTURE").endsWith("visible evidence):\nfixture user rule");
                    assertThat(root.at("/messages/1/content/1/image_url/url").asText()).isEqualTo("data:image/png;base64,AQIDBA==");
                })).andRespond(withSuccess(RESPONSE,MediaType.APPLICATION_JSON));
        var result = adapter.analyze(image("image/png; charset=test"),"payment-capture","  fixture user rule  ");
        assertThat(result.ok()).isTrue(); assertThat(result.documentType()).isEqualTo("RECEIPT");
        assertThat(result.rawText()).isEqualTo("fixture image"); assertThat(result.parsedEntries()).isEmpty();
        assertThat(result.timing()).containsEntry("engine","lmstudio-vision");
        verify(queue).execute(any(),any()); assertMetric("success"); server.verify();
    }

    @Test void nativeOllamaKeepsImageArrayFormatAndDoesNotSendOpenAiResponseSchema() {
        properties.getImage().setProvider("ollama"); properties.getImage().setBaseUrl(BASE); properties.getImage().setChatPath("/api/chat");
        server.expect(requestTo(BASE+"/api/chat")).andExpect(body(root -> {
            assertThat(root.path("format").asText()).isEqualTo("json");
            assertThat(root.at("/options/temperature").asDouble()).isEqualTo(0.05);
            assertThat(root.at("/options/num_predict").asInt()).isEqualTo(4096);
            assertThat(root.has("response_format")).isFalse(); assertThat(root.has("max_tokens")).isFalse();
            assertThat(root.at("/messages/1/content").isTextual()).isTrue();
            assertThat(root.at("/messages/1/images/0").asText()).isEqualTo("AQIDBA==");
        })).andRespond(withSuccess(RESPONSE,MediaType.APPLICATION_JSON));
        adapter.analyze(image(null),"AUTO",null); verify(queue).execute(any(),any()); server.verify();
    }

    @ParameterizedTest @ValueSource(strings={"{\"data\":[{\"id\":\"auto-vision\"}]}","{\"models\":[{\"name\":\"auto-vision\"}]}","[\"auto-vision\"]"})
    void authenticatedModelDiscoveryPreservesAllExistingShapes(String models) {
        properties.getImage().setModel("auto");
        server.expect(requestTo(BASE+"/v1/models")).andExpect(method(HttpMethod.GET)).andExpect(header("Authorization","Bearer fixture-only-token"))
                .andRespond(withSuccess(models,MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE+"/v1/chat/completions")).andExpect(body(root -> assertThat(root.path("model").asText()).isEqualTo("auto-vision")))
                .andRespond(withSuccess(RESPONSE,MediaType.APPLICATION_JSON));
        adapter.analyze(image(null),null,null); server.verify();
    }

    @Test void emptyModelDiscoveryFailsBeforeReadingImageOrQueueAdmission() {
        properties.getImage().setModel("auto"); ImageContent image = mock(ImageContent.class);
        server.expect(requestTo(BASE+"/v1/models")).andRespond(withSuccess("{\"data\":[]}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.analyze(image,"AUTO",null)).hasMessage("LM Studio has no loaded model. Load a vision-capable model first.");
        verifyNoInteractions(image); verify(queue,never()).execute(any(),any()); assertMetric("failure"); server.verify();
    }

    @Test void absentContentTypeStillUsesJpegDataUrl() {
        server.expect(requestTo(BASE+"/v1/chat/completions")).andExpect(body(root ->
                assertThat(root.at("/messages/1/content/1/image_url/url").asText()).isEqualTo("data:image/jpeg;base64,AQIDBA==")))
                .andRespond(withSuccess(RESPONSE,MediaType.APPLICATION_JSON));
        adapter.analyze(image(null),"AUTO",null); server.verify();
    }

    @Test void providerHttpFailureKeepsExistingNoRetryBehaviorAndHidesPrivateBody() {
        server.expect(requestTo(BASE+"/v1/chat/completions")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body("response_format unsupported fixture-only-token private fixture"));
        assertThatThrownBy(() -> adapter.analyze(image("image/png"),"AUTO",null)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("연결할 수 없습니다").hasMessageNotContaining("private fixture").hasMessageNotContaining("fixture-only-token");
        verify(queue).execute(any(),any()); assertMetric("failure"); server.verify();
    }

    @Test void lazyImageReadFailureRetainsTheGenericConnectionErrorAndFailureMetric() {
        ImageContent image = mock(ImageContent.class); when(image.contentType()).thenReturn("image/png");
        when(image.bytes()).thenThrow(new ImageReadFailure(new IOException("private file fixture")));
        assertThatThrownBy(() -> adapter.analyze(image,"AUTO",null)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("연결할 수 없습니다").hasMessageNotContaining("private file fixture");
        verify(queue,never()).execute(any(),any()); assertMetric("failure"); server.verify();
    }

    @Test void serverDeclaredFailureRetainsTheExistingReviewableError() {
        server.expect(requestTo(BASE+"/v1/chat/completions")).andRespond(withSuccess("{\"ok\":false,\"error\":\"fixture unreadable image\"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.analyze(image(null),"AUTO",null)).hasMessage("fixture unreadable image");
        assertMetric("failure"); server.verify();
    }

    @Test void emptyResponseIsStillAValidationError() {
        server.expect(requestTo(BASE+"/v1/chat/completions")).andRespond(withSuccess("",MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.analyze(image(null),"AUTO",null)).hasMessageContaining("빈 응답");
        assertMetric("failure"); server.verify();
    }

    @Test void directPortProxySuspendsCallerTransactionDuringFileReadQueueAndHttpThenRestoresIt() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:image-extraction-boundary;DB_CLOSE_DELAY=-1","sa","");
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var proxy = new ProxyFactory(adapter); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactionManager,new AnnotationTransactionAttributeSource()));
        LedgerImageExtractionPort port = (LedgerImageExtractionPort)proxy.getProxy();
        ImageContent image = mock(ImageContent.class); when(image.contentType()).thenReturn("image/png");
        when(image.bytes()).thenAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return new byte[]{1}; });
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(queue).execute(any(),any());
        server.expect(requestTo(BASE+"/v1/chat/completions")).andExpect(request ->
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()).andRespond(withSuccess(RESPONSE,MediaType.APPLICATION_JSON));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(port.analyze(image,"AUTO",null).ok()).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); status.setRollbackOnly();
        });
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(image).bytes(); verify(queue).execute(any(),any()); server.verify();
    }
}
