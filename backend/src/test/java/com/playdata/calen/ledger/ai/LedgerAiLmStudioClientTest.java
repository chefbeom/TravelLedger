package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LedgerAiLmStudioClientTest {

    private final LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
    private final LedgerAiLmStudioClient client = new LedgerAiLmStudioClient(
            properties,
            new ObjectMapper().findAndRegisterModules(),
            new LedgerAiRequestQueue()
    );

    @Test
    void extractsFirstModelFromLmStudioDataArray() {
        String modelId = ReflectionTestUtils.invokeMethod(
                client,
                "extractFirstModelId",
                "{\"data\":[{\"id\":\"qwen2.5-7b-instruct\"},{\"id\":\"backup\"}]}"
        );

        assertThat(modelId).isEqualTo("qwen2.5-7b-instruct");
    }

    @Test
    void extractsFirstModelFromAlternateModelsArray() {
        String modelId = ReflectionTestUtils.invokeMethod(
                client,
                "extractFirstModelId",
                "{\"models\":[{\"name\":\"gemma-local\"}]}"
        );

        assertThat(modelId).isEqualTo("gemma-local");
    }

    @Test
    void extractsAllModelsInStableOrderForAutoFallback() {
        java.util.List<String> modelIds = ReflectionTestUtils.invokeMethod(
                client,
                "extractModelIds",
                "{\"data\":[{\"id\":\"busy-model\"},{\"id\":\"backup-model\"},{\"id\":\"busy-model\"}]}"
        );

        assertThat(modelIds).containsExactly("busy-model", "backup-model");
    }

    @Test
    void recognizesModelCapacityErrorAndExplainsFallback() {
        Boolean capacity = ReflectionTestUtils.invokeMethod(
                client,
                "isModelCapacityError",
                "Selected model is at capacity. Please try a different model."
        );
        BadRequestException failure = ReflectionTestUtils.invokeMethod(
                client,
                "modelCapacityFailure",
                java.util.List.of("busy-model", "backup-model")
        );

        assertThat(capacity).isTrue();
        assertThat(failure).hasMessageContaining("All available LM Studio models are at capacity");
    }

    @Test
    void rejectsEmptyModelListWithoutLeakingProviderSecrets() {
        properties.setLmStudioBaseUrl("http://secret-lmstudio.internal:1234");
        properties.setLmStudioApiKey("lmstudio-secret-token");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(client, "extractFirstModelId", "{\"data\":[]}"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("LM Studio")
                .hasMessageNotContaining("secret-lmstudio.internal")
                .hasMessageNotContaining("lmstudio-secret-token");
    }

    @Test
    void extractsAssistantContentFromOpenAiLikeChatResponse() {
        String content = ReflectionTestUtils.invokeMethod(
                client,
                "extractAssistantContent",
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"summary\\\":\\\"ok\\\"}\"}}]}"
        );

        assertThat(content).isEqualTo("{\"summary\":\"ok\"}");
    }
}