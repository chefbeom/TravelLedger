package com.playdata.calen.ledger.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ledger.ai")
public class LedgerAiAnalysisProperties {

    private boolean enabled = false;
    private String workflowUrl = "";
    private String apiKey = "";
    private String apiKeyHeader = "X-TravelLedger-AI-Key";
    private String model = "gemma4:e12b";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(120);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWorkflowUrl() {
        return workflowUrl;
    }

    public void setWorkflowUrl(String workflowUrl) {
        this.workflowUrl = workflowUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKeyHeader() {
        return apiKeyHeader;
    }

    public void setApiKeyHeader(String apiKeyHeader) {
        this.apiKeyHeader = apiKeyHeader;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public boolean isWorkflowConfigured() {
        return workflowUrl != null && !workflowUrl.isBlank();
    }

    public boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isConfigured() {
        return enabled && isWorkflowConfigured();
    }

    public String statusMessage() {
        if (!enabled) {
            return "AI 분석 기능이 비활성화되어 있습니다. APP_LEDGER_AI_ENABLED=true로 설정하세요.";
        }
        if (!isWorkflowConfigured()) {
            return "n8n 웹훅 URL이 설정되지 않았습니다. APP_LEDGER_AI_WORKFLOW_URL을 설정하세요.";
        }
        if (!isApiKeyConfigured()) {
            return "n8n 웹훅 API 키가 비어 있습니다. 마지막 설정 단계에서 APP_LEDGER_AI_API_KEY를 입력할 수 있습니다.";
        }
        return "AI 분석 준비가 완료되었습니다.";
    }
}
