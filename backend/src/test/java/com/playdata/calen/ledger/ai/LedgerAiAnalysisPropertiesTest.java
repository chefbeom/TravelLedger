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
}
