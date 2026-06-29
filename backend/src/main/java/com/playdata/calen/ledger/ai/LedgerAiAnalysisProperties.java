package com.playdata.calen.ledger.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ledger.ai")
public class LedgerAiAnalysisProperties {

    private boolean enabled = false;
    private String provider = "lmstudio";
    private String workflowUrl = "";
    private String apiKey = "";
    private String apiKeyHeader = "X-TravelLedger-AI-Key";
    private String model = "gemma4:e12b";
    private String lmStudioBaseUrl = "http://172.18.240.1:1234";
    private String lmStudioChatPath = "/api/v1/chat";
    private String lmStudioApiKey = "";
    private double temperature = 0.2;
    private int maxTokens = 2048;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(120);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public LedgerAiProvider provider() {
        return LedgerAiProvider.from(provider);
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

    public String getLmStudioBaseUrl() {
        return lmStudioBaseUrl;
    }

    public void setLmStudioBaseUrl(String lmStudioBaseUrl) {
        this.lmStudioBaseUrl = lmStudioBaseUrl;
    }

    public String getLmStudioChatPath() {
        return lmStudioChatPath;
    }

    public void setLmStudioChatPath(String lmStudioChatPath) {
        this.lmStudioChatPath = lmStudioChatPath;
    }

    public String normalizedLmStudioChatPath() {
        if (lmStudioChatPath == null || lmStudioChatPath.isBlank()) {
            return "/api/v1/chat";
        }
        return lmStudioChatPath.startsWith("/") ? lmStudioChatPath : "/" + lmStudioChatPath;
    }

    public String getLmStudioApiKey() {
        return lmStudioApiKey;
    }

    public void setLmStudioApiKey(String lmStudioApiKey) {
        this.lmStudioApiKey = lmStudioApiKey;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
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

    public boolean isLmStudioConfigured() {
        return hasText(lmStudioBaseUrl) && hasText(model);
    }

    public boolean isConfigured() {
        if (!enabled) {
            return false;
        }
        if (provider() == LedgerAiProvider.LMSTUDIO) {
            return isLmStudioConfigured();
        }
        return isWorkflowConfigured();
    }

    public String statusMessage() {
        if (!enabled) {
            return "AI 분석 기능이 비활성화되어 있습니다. APP_LEDGER_AI_ENABLED=true로 설정하세요.";
        }
        if (provider() == LedgerAiProvider.LMSTUDIO) {
            if (!isLmStudioConfigured()) {
                return "LM Studio 연결 정보가 부족합니다. APP_LEDGER_AI_LMSTUDIO_BASE_URL과 APP_LEDGER_AI_MODEL을 설정하세요.";
            }
            return "LM Studio AI 분석 준비가 완료되었습니다.";
        }
        if (!isWorkflowConfigured()) {
            return "n8n 웹훅 URL이 설정되지 않았습니다. APP_LEDGER_AI_WORKFLOW_URL을 설정하세요.";
        }
        if (!isApiKeyConfigured()) {
            return "n8n 웹훅 API 키가 비어 있습니다. 마지막 설정 단계에서 APP_LEDGER_AI_API_KEY를 입력할 수 있습니다.";
        }
        return "n8n AI 분석 준비가 완료되었습니다.";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
