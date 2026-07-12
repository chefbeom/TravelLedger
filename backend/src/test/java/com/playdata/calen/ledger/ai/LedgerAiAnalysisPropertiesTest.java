package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LedgerAiAnalysisPropertiesTest {

    @Test
    void autoLmStudioModelKeepsProviderConfigured() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setEnabled(true);
        properties.setModel("auto");

        assertThat(properties.isLmStudioModelAuto()).isTrue();
        assertThat(properties.normalizedLmStudioModel()).isEmpty();
        assertThat(properties.isLmStudioConfigured()).isTrue();
        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.statusMessage()).contains("/v1/models");
    }

    @Test
    void normalizesLmStudioEndpointPaths() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setLmStudioModelsPath("api/v1/models");
        properties.setLmStudioChatPath("api/v1/chat");
        properties.setModel(" qwen2.5 ");

        assertThat(properties.normalizedLmStudioModelsPath()).isEqualTo("/api/v1/models");
        assertThat(properties.normalizedLmStudioChatPath()).isEqualTo("/api/v1/chat");
        assertThat(properties.isLmStudioModelAuto()).isFalse();
        assertThat(properties.normalizedLmStudioModel()).isEqualTo("qwen2.5");
    }
    @Test
    void allowsAnyProviderHostWhenAllowlistIsNotEnforced() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setEnabled(true);
        properties.setEnforceProviderUrlAllowlist(false);
        properties.setLmStudioBaseUrl("http://169.254.169.254/latest/meta-data");

        assertThat(properties.isLmStudioConfigured()).isTrue();
        assertThat(properties.isConfigured()).isTrue();
    }

    @Test
    void rejectsNonHttpAndCredentialBearingProviderUrlsEvenWithoutAllowlist() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setEnforceProviderUrlAllowlist(false);

        assertThat(properties.isProviderUrlAllowed("file:///etc/passwd")).isFalse();
        assertThat(properties.isProviderUrlAllowed("ftp://localhost/resource")).isFalse();
        assertThat(properties.isProviderUrlAllowed("http://user:password@localhost:1234")).isFalse();
    }

    @Test
    void rejectsHostOverridingLmStudioEndpointPaths() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setLmStudioChatPath("//169.254.169.254/latest/meta-data");
        properties.setLmStudioModelsPath("https://169.254.169.254/models");

        assertThat(properties.hasSafeLmStudioEndpointPaths()).isFalse();
        assertThat(properties.normalizedLmStudioChatPath()).isEqualTo("/v1/chat/completions");
        assertThat(properties.normalizedLmStudioModelsPath()).isEqualTo("/v1/models");
        assertThat(properties.isLmStudioConfigured()).isFalse();
    }

    @Test
    void rejectsDisallowedLmStudioHostWhenAllowlistIsEnforced() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setEnabled(true);
        properties.setEnforceProviderUrlAllowlist(true);
        properties.setAllowedProviderHosts("localhost,127.0.0.1");
        properties.setLmStudioBaseUrl("http://169.254.169.254/latest/meta-data");

        assertThat(properties.isProviderUrlAllowed(properties.getLmStudioBaseUrl())).isFalse();
        assertThat(properties.isLmStudioConfigured()).isFalse();
        assertThat(properties.isConfigured()).isFalse();
        assertThat(properties.statusMessage()).contains("allowlist");
    }

    @Test
    void allowsConfiguredLmStudioHostWhenAllowlistIsEnforced() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setEnabled(true);
        properties.setEnforceProviderUrlAllowlist(true);
        properties.setAllowedProviderHosts(" localhost, LMSTUDIO.local ");
        properties.setLmStudioBaseUrl("http://lmstudio.local:1234");

        assertThat(properties.isProviderUrlAllowed(properties.getLmStudioBaseUrl())).isTrue();
        assertThat(properties.isConfigured()).isTrue();
    }

    @Test
    void allowsBracketedIpv6N8nHostWhenAllowlistIsEnforced() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setEnabled(true);
        properties.setProvider("n8n");
        properties.setEnforceProviderUrlAllowlist(true);
        properties.setAllowedProviderHosts("::1,localhost");
        properties.setWorkflowUrl("http://[::1]:5678/webhook/travelledger-ledger-ai");

        assertThat(properties.isProviderUrlAllowed(properties.getWorkflowUrl())).isTrue();
        assertThat(properties.isConfigured()).isTrue();
    }

    @Test
    void rejectsInvalidProviderUrlWhenAllowlistIsEnforced() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setEnabled(true);
        properties.setEnforceProviderUrlAllowlist(true);
        properties.setAllowedProviderHosts("localhost");
        properties.setLmStudioBaseUrl("not a uri");

        assertThat(properties.isProviderUrlAllowed(properties.getLmStudioBaseUrl())).isFalse();
        assertThat(properties.isConfigured()).isFalse();
    }

    @Test
    void configuresOpenAiWithOfficialApiHostAndExplicitModel() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setEnabled(true);
        properties.setProvider("openai");
        properties.setAllowedProviderHosts("api.openai.com");
        properties.setOpenAiBaseUrl("https://api.openai.com");
        properties.setOpenAiApiKey("test-openai-key");
        properties.setModel("gpt-4.1-mini");

        assertThat(properties.provider()).isEqualTo(LedgerAiProvider.OPENAI);
        assertThat(properties.isOpenAiConfigured()).isTrue();
        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.activeOpenAiCompatibleBaseUrl()).isEqualTo("https://api.openai.com");
        assertThat(properties.activeOpenAiCompatibleChatPath()).isEqualTo("/v1/chat/completions");
        assertThat(properties.activeOpenAiCompatibleModelsPath()).isEqualTo("/v1/models");
        assertThat(properties.openAiCompatibleProviderLabel()).isEqualTo("OpenAI API");
    }

    @Test
    void requiresAnOpenAiApiKeyAndExplicitModel() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setEnabled(true);
        properties.setProvider("openai");
        properties.setAllowedProviderHosts("api.openai.com");
        properties.setOpenAiBaseUrl("https://api.openai.com");
        properties.setModel("auto");

        assertThat(properties.isOpenAiConfigured()).isFalse();
        assertThat(properties.statusMessage()).contains("API key");

        properties.setOpenAiApiKey("test-openai-key");

        assertThat(properties.isOpenAiConfigured()).isFalse();
        assertThat(properties.statusMessage()).contains("model is required");
    }
    @Test
    void resolvesLedgerAndImageProfilesIndependently() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setAllowedProviderHosts("api.openai.com,ollama.local");

        properties.getLedger().setProvider("openai");
        properties.getLedger().setBaseUrl("https://api.openai.com");
        properties.getLedger().setModel("gpt-4.1-mini");
        properties.getLedger().setApiKey("ledger-key");

        properties.getImage().setProvider("ollama");
        properties.getImage().setBaseUrl("http://ollama.local:11434");
        properties.getImage().setModel("llama3.2-vision");

        LedgerAiFeatureConfig ledger = properties.featureConfig(LedgerAiFeature.LEDGER_ANALYSIS);
        LedgerAiFeatureConfig image = properties.featureConfig(LedgerAiFeature.IMAGE_ANALYSIS);

        assertThat(ledger.provider()).isEqualTo(LedgerAiProvider.OPENAI);
        assertThat(ledger.baseUrl()).isEqualTo("https://api.openai.com");
        assertThat(ledger.apiKey()).isEqualTo("ledger-key");
        assertThat(image.provider()).isEqualTo(LedgerAiProvider.OLLAMA);
        assertThat(image.chatPath()).isEqualTo("/api/chat");
        assertThat(image.modelsPath()).isEqualTo("/api/tags");
        assertThat(properties.isFeatureConfigured(LedgerAiFeature.LEDGER_ANALYSIS)).isTrue();
        assertThat(properties.isFeatureConfigured(LedgerAiFeature.IMAGE_ANALYSIS)).isTrue();
    }

    @Test
    void featureProfileFallsBackToLegacySettingsUntilConfigured() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setProvider("lmstudio");
        properties.setLmStudioBaseUrl("http://localhost:1234");
        properties.setModel("qwen2.5-vl");

        LedgerAiFeatureConfig image = properties.featureConfig(LedgerAiFeature.IMAGE_ANALYSIS);

        assertThat(image.provider()).isEqualTo(LedgerAiProvider.LMSTUDIO);
        assertThat(image.baseUrl()).isEqualTo("http://localhost:1234");
        assertThat(image.model()).isEqualTo("qwen2.5-vl");
    }
}
