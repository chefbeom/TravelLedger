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
            new ObjectMapper().findAndRegisterModules()
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