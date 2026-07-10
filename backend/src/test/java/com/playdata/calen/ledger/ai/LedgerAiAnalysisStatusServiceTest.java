package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.playdata.calen.ledger.dto.LedgerAiAnalysisStatusResponse;
import org.junit.jupiter.api.Test;

class LedgerAiAnalysisStatusServiceTest {

    @Test
    void getStatusReturnsReadinessWithoutProviderSecretsOrUrls() {
        LedgerAiAnalysisProperties properties = new LedgerAiAnalysisProperties();
        properties.setEnabled(true);
        properties.setProvider("lmstudio");
        properties.setModel("auto");
        properties.setLmStudioBaseUrl("http://172.18.240.1:1234");
        properties.setAllowedProviderHosts("172.18.240.1,127.0.0.1");
        properties.setLmStudioApiKey("lmstudio-secret-token");
        properties.setWorkflowUrl("http://127.0.0.1:5678/webhook/travelledger-ledger-ai");
        properties.setApiKey("n8n-secret-token");

        LedgerAiAnalysisStatusResponse response = new LedgerAiAnalysisStatusService(properties).getStatus();

        assertThat(response.enabled()).isTrue();
        assertThat(response.configured()).isTrue();
        assertThat(response.provider()).isEqualTo("lmstudio");
        assertThat(response.lmStudioConfigured()).isTrue();
        assertThat(response.model()).isEqualTo("auto");
        assertThat(response.toString())
                .doesNotContain("http://172.18.240.1:1234")
                .doesNotContain("http://127.0.0.1:5678")
                .doesNotContain("lmstudio-secret-token")
                .doesNotContain("n8n-secret-token");
    }
}