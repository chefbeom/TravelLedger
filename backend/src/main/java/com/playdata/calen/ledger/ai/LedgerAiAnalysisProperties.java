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
    private String lmStudioBaseUrl = "http://localhost:1234";
    private String lmStudioModelsPath = "/v1/models";
    private String lmStudioChatPath = "/v1/chat/completions";
    private String lmStudioApiKey = "";
    private String openAiBaseUrl = "https://api.openai.com";
    private String openAiModelsPath = "/v1/models";
    private String openAiChatPath = "/v1/chat/completions";
    private String openAiApiKey = "";
    private double temperature = 0.2;
    private int maxTokens = 4096;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofMinutes(10);
    private boolean enforceProviderUrlAllowlist = true;
    private String allowedProviderHosts = "localhost,127.0.0.1,::1,api.openai.com";

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
        return normalizeRelativeEndpointPath(lmStudioModelsPath, "/v1/models");
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
        return normalizeRelativeEndpointPath(lmStudioChatPath, "/v1/chat/completions");
    }

    public String getLmStudioApiKey() {
        return lmStudioApiKey;
    }

    public void setLmStudioApiKey(String lmStudioApiKey) {
        this.lmStudioApiKey = lmStudioApiKey;
    }

    public String getOpenAiBaseUrl() {
        return openAiBaseUrl;
    }

    public void setOpenAiBaseUrl(String openAiBaseUrl) {
        this.openAiBaseUrl = openAiBaseUrl;
    }

    public String getOpenAiModelsPath() {
        return openAiModelsPath;
    }

    public void setOpenAiModelsPath(String openAiModelsPath) {
        this.openAiModelsPath = openAiModelsPath;
    }

    public String normalizedOpenAiModelsPath() {
        if (openAiModelsPath == null || openAiModelsPath.isBlank()) {
            return "/v1/models";
        }
        return normalizeRelativeEndpointPath(openAiModelsPath, "/v1/models");
    }

    public String getOpenAiChatPath() {
        return openAiChatPath;
    }

    public void setOpenAiChatPath(String openAiChatPath) {
        this.openAiChatPath = openAiChatPath;
    }

    public String normalizedOpenAiChatPath() {
        if (openAiChatPath == null || openAiChatPath.isBlank()) {
            return "/v1/chat/completions";
        }
        return normalizeRelativeEndpointPath(openAiChatPath, "/v1/chat/completions");
    }

    public String getOpenAiApiKey() {
        return openAiApiKey;
    }

    public void setOpenAiApiKey(String openAiApiKey) {
        this.openAiApiKey = openAiApiKey;
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
        return hasText(lmStudioBaseUrl)
                && isProviderUrlAllowed(lmStudioBaseUrl)
                && hasSafeLmStudioEndpointPaths();
    }

    public boolean isOpenAiConfigured() {
        return hasText(openAiBaseUrl)
                && isProviderUrlAllowed(openAiBaseUrl)
                && hasSafeOpenAiEndpointPaths()
                && hasText(openAiApiKey)
                && !isOpenAiModelAuto();
    }

    public boolean isLmStudioModelAuto() {
        return !hasText(model) || "auto".equalsIgnoreCase(model.trim());
    }

    public boolean isOpenAiModelAuto() {
        return !hasText(model) || "auto".equalsIgnoreCase(model.trim());
    }

    public String normalizedLmStudioModel() {
        return isLmStudioModelAuto() ? "" : model.trim();
    }

    public String normalizedOpenAiModel() {
        return isOpenAiModelAuto() ? "" : model.trim();
    }

    public String activeOpenAiCompatibleBaseUrl() {
        return provider() == LedgerAiProvider.OPENAI ? openAiBaseUrl : lmStudioBaseUrl;
    }

    public String activeOpenAiCompatibleModelsPath() {
        return provider() == LedgerAiProvider.OPENAI ? normalizedOpenAiModelsPath() : normalizedLmStudioModelsPath();
    }

    public String activeOpenAiCompatibleChatPath() {
        return provider() == LedgerAiProvider.OPENAI ? normalizedOpenAiChatPath() : normalizedLmStudioChatPath();
    }

    public String activeOpenAiCompatibleApiKey() {
        return provider() == LedgerAiProvider.OPENAI ? openAiApiKey : lmStudioApiKey;
    }

    public String activeOpenAiCompatibleModel() {
        return provider() == LedgerAiProvider.OPENAI ? normalizedOpenAiModel() : normalizedLmStudioModel();
    }

    public String openAiCompatibleProviderLabel() {
        return provider() == LedgerAiProvider.OPENAI ? "OpenAI API" : "LM Studio";
    }

    public boolean isConfigured() {
        if (!enabled) {
            return false;
        }
        return switch (provider()) {
            case LMSTUDIO -> isLmStudioConfigured();
            case OPENAI -> isOpenAiConfigured();
            case N8N -> isWorkflowConfigured();
        };
    }

    public String statusMessage() {
        if (!enabled) {
            return "AI analysis is disabled. Set APP_LEDGER_AI_ENABLED=true to enable it.";
        }
        if (provider() == LedgerAiProvider.LMSTUDIO) {
            if (!hasText(lmStudioBaseUrl)) {
                return "LM Studio URL is missing. Set APP_LEDGER_AI_LMSTUDIO_BASE_URL.";
            }
            if (!isProviderUrlAllowed(lmStudioBaseUrl)) {
                return "LM Studio URL must use HTTP(S), and its host must be in the AI provider allowlist (APP_LEDGER_AI_ALLOWED_PROVIDER_HOSTS).";
            }
            if (!hasSafeLmStudioEndpointPaths()) {
                return "LM Studio chat and models endpoints must be relative paths on the configured server.";
            }
            if (isLmStudioModelAuto()) {
                return "LM Studio AI analysis is ready. The model will be selected from "
                        + normalizedLmStudioModelsPath() + ".";
            }
            return "LM Studio AI analysis is ready.";
        }
        if (provider() == LedgerAiProvider.OPENAI) {
            if (!hasText(openAiBaseUrl)) {
                return "OpenAI API base URL is missing. Set APP_LEDGER_AI_OPENAI_BASE_URL.";
            }
            if (!isProviderUrlAllowed(openAiBaseUrl)) {
                return "OpenAI API URL must use HTTP(S), and its host must be in the AI provider allowlist (APP_LEDGER_AI_ALLOWED_PROVIDER_HOSTS).";
            }
            if (!hasSafeOpenAiEndpointPaths()) {
                return "OpenAI API chat and models endpoints must be relative paths on the configured server.";
            }
            if (!hasText(openAiApiKey)) {
                return "OpenAI API key is missing. Set APP_LEDGER_AI_OPENAI_API_KEY.";
            }
            if (isOpenAiModelAuto()) {
                return "OpenAI API model is required. Set APP_LEDGER_AI_MODEL.";
            }
            return "OpenAI API analysis is ready.";
        }
        if (!hasText(workflowUrl)) {
            return "n8n webhook URL is missing. Set APP_LEDGER_AI_WORKFLOW_URL.";
        }
        if (!isProviderUrlAllowed(workflowUrl)) {
            return "n8n webhook host is not in the AI provider allowlist. Check APP_LEDGER_AI_ALLOWED_PROVIDER_HOSTS.";
        }
        if (!isApiKeyConfigured()) {
            return "n8n webhook API key is missing. Set APP_LEDGER_AI_API_KEY.";
        }
        return "n8n AI analysis is ready.";
    }
    public boolean isProviderUrlAllowed(String value) {
        URI providerUri = parseProviderUri(value);
        if (providerUri == null) {
            return false;
        }
        if (!enforceProviderUrlAllowlist) {
            return true;
        }

        String host = normalizeHost(providerUri.getHost());
        for (String allowedHost : safeText(allowedProviderHosts).split(",")) {
            if (host.equals(normalizeHost(allowedHost))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSafeLmStudioEndpointPaths() {
        return isSafeRelativeEndpointPath(lmStudioChatPath)
                && isSafeRelativeEndpointPath(lmStudioModelsPath);
    }

    public boolean hasSafeOpenAiEndpointPaths() {
        return isSafeRelativeEndpointPath(openAiChatPath)
                && isSafeRelativeEndpointPath(openAiModelsPath);
    }

    private URI parseProviderUri(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = safeText(uri.getScheme()).toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || !hasText(uri.getHost())
                    || hasText(uri.getUserInfo())) {
                return null;
            }
            return uri;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private String normalizeRelativeEndpointPath(String value, String fallback) {
        if (!isSafeRelativeEndpointPath(value)) {
            return fallback;
        }
        String path = value.trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    private boolean isSafeRelativeEndpointPath(String value) {
        String path = safeText(value).trim();
        if (!hasText(path)
                || path.startsWith("//")
                || path.contains("\\")
                || path.contains("?")
                || path.contains("#")
                || path.indexOf('\r') >= 0
                || path.indexOf('\n') >= 0) {
            return false;
        }
        try {
            URI uri = new URI(path);
            return !uri.isAbsolute() && uri.getRawAuthority() == null;
        } catch (URISyntaxException exception) {
            return false;
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
