package com.playdata.calen.ledger.ai;

public class LedgerAiFeatureSettings {

    private String provider = "";
    private String model = "";
    private String baseUrl = "";
    private String chatPath = "";
    private String modelsPath = "";
    private String apiKey = "";
    private Double temperature;
    private Integer maxTokens;
    private boolean enabled = true;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getChatPath() { return chatPath; }
    public void setChatPath(String chatPath) { this.chatPath = chatPath; }
    public String getModelsPath() { return modelsPath; }
    public void setModelsPath(String modelsPath) { this.modelsPath = modelsPath; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}