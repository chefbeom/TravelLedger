package com.playdata.calen.ledger.ai;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ledger.ai")
public class LedgerAiAnalysisProperties {

    private boolean enabled = true;
    private String provider = "lmstudio";
    private String workflowUrl = "";
    private String apiKey = "";
    private String apiKeyHeader = "X-TravelLedger-AI-Key";
    private String model = "auto";
    private String lmStudioBaseUrl = "http://100.92.170.22:1234";
    private String lmStudioModelsPath = "/v1/models";
    private String lmStudioChatPath = "/v1/chat/completions";
    private String lmStudioApiKey = "";
    private double temperature = 0.2;
    private int maxTokens = 2048;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(120);
    private boolean enforceProviderUrlAllowlist = false;
    private String allowedProviderHosts = "localhost,127.0.0.1,::1,100.92.170.22";

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

    public String getLmStudioModelsPath() {
        return lmStudioModelsPath;
    }

    public void setLmStudioModelsPath(String lmStudioModelsPath) {
        this.lmStudioModelsPath = lmStudioModelsPath;
    }

    public String normalizedLmStudioModelsPath() {
        if (lmStudioModelsPath == null || lmStudioModelsPath.isBlank()) {
            return "/v1/models";
        }
        return lmStudioModelsPath.startsWith("/") ? lmStudioModelsPath : "/" + lmStudioModelsPath;
    }

    public String getLmStudioChatPath() {
        return lmStudioChatPath;
    }

    public void setLmStudioChatPath(String lmStudioChatPath) {
        this.lmStudioChatPath = lmStudioChatPath;
    }

    public String normalizedLmStudioChatPath() {
        if (lmStudioChatPath == null || lmStudioChatPath.isBlank()) {
            return "/v1/chat/completions";
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

    public boolean isEnforceProviderUrlAllowlist() {
        return enforceProviderUrlAllowlist;
    }

    public void setEnforceProviderUrlAllowlist(boolean enforceProviderUrlAllowlist) {
        this.enforceProviderUrlAllowlist = enforceProviderUrlAllowlist;
    }

    public String getAllowedProviderHosts() {
        return allowedProviderHosts;
    }

    public void setAllowedProviderHosts(String allowedProviderHosts) {
        this.allowedProviderHosts = allowedProviderHosts;
    }

    public boolean isWorkflowConfigured() {
        return hasText(workflowUrl) && isProviderUrlAllowed(workflowUrl);
    }

    public boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isLmStudioConfigured() {
        return hasText(lmStudioBaseUrl) && isProviderUrlAllowed(lmStudioBaseUrl);
    }

    public boolean isLmStudioModelAuto() {
        return !hasText(model) || "auto".equalsIgnoreCase(model.trim());
    }

    public String normalizedLmStudioModel() {
        return isLmStudioModelAuto() ? "" : model.trim();
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
            return "AI 遺꾩꽍 湲곕뒫??鍮꾪솢?깊솕?섏뼱 ?덉뒿?덈떎. APP_LEDGER_AI_ENABLED=true濡??ㅼ젙?섏꽭??";
        }
        if (provider() == LedgerAiProvider.LMSTUDIO) {
            if (!hasText(lmStudioBaseUrl)) {
                return "LM Studio ?곌껐 ?뺣낫媛 遺議깊빀?덈떎. APP_LEDGER_AI_LMSTUDIO_BASE_URL???ㅼ젙?섏꽭??";
            }
            if (!isProviderUrlAllowed(lmStudioBaseUrl)) {
                return "LM Studio ?몄뒪?멸? AI provider allowlist???놁뒿?덈떎. APP_LEDGER_AI_ALLOWED_PROVIDER_HOSTS瑜??뺤씤?섏꽭??";
            }
            if (isLmStudioModelAuto()) {
                return "LM Studio AI 遺꾩꽍 以鍮꾧? ?꾨즺?섏뿀?듬땲?? 紐⑤뜽? /api/v1/models?먯꽌 ?먮룞 ?좏깮?⑸땲??";
            }
            return "LM Studio AI 遺꾩꽍 以鍮꾧? ?꾨즺?섏뿀?듬땲??";
        }
        if (!hasText(workflowUrl)) {
            return "n8n ?뱁썒 URL???ㅼ젙?섏? ?딆븯?듬땲?? APP_LEDGER_AI_WORKFLOW_URL???ㅼ젙?섏꽭??";
        }
        if (!isProviderUrlAllowed(workflowUrl)) {
            return "n8n ?뱁썒 ?몄뒪?멸? AI provider allowlist???놁뒿?덈떎. APP_LEDGER_AI_ALLOWED_PROVIDER_HOSTS瑜??뺤씤?섏꽭??";
        }
        if (!isApiKeyConfigured()) {
            return "n8n ?뱁썒 API ?ㅺ? 鍮꾩뼱 ?덉뒿?덈떎. 留덉?留??ㅼ젙 ?④퀎?먯꽌 APP_LEDGER_AI_API_KEY瑜??낅젰?????덉뒿?덈떎.";
        }
        return "n8n AI 遺꾩꽍 以鍮꾧? ?꾨즺?섏뿀?듬땲??";
    }

    public boolean isProviderUrlAllowed(String value) {
        if (!enforceProviderUrlAllowlist) {
            return true;
        }
        String host = normalizeHost(extractHost(value));
        if (!hasText(host)) {
            return false;
        }
        for (String allowedHost : safeText(allowedProviderHosts).split(",")) {
            if (host.equals(normalizeHost(allowedHost))) {
                return true;
            }
        }
        return false;
    }

    private String extractHost(String value) {
        if (!hasText(value)) {
            return "";
        }
        try {
            return new URI(value.trim()).getHost();
        } catch (URISyntaxException exception) {
            return "";
        }
    }

    private String normalizeHost(String value) {
        String host = safeText(value).trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
