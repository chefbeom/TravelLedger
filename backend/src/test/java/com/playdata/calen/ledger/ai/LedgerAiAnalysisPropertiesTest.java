package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LedgerAiAnalysisPropertiesTest {

    @Test
    void allowsAnyProviderHostWhenAllowlistIsNotEnforced() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setEnabled(true);
        properties.setLmStudioBaseUrl("http://169.254.169.254/latest/meta-data");

        assertThat(properties.isLmStudioConfigured()).isTrue();
        assertThat(properties.isConfigured()).isTrue();
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
