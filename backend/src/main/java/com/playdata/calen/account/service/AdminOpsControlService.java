package com.playdata.calen.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.dto.AdminAiControlResponse;
import com.playdata.calen.account.dto.AdminAiFeatureConfigResponse;
import com.playdata.calen.account.security.AdminOpsSecretCipher;
import com.playdata.calen.account.dto.AdminAiControlUpdateRequest;
import com.playdata.calen.account.dto.AdminAiServerStatusResponse;
import com.playdata.calen.account.dto.AdminDataServerStatusResponse;
import com.playdata.calen.account.dto.AdminDataStorageControlUpdateRequest;
import com.playdata.calen.account.dto.AdminMinioStorageSummaryResponse;
import com.playdata.calen.account.dto.AdminOpsControlResponse;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisProperties;
import com.playdata.calen.ledger.ai.LedgerAiProvider;
import com.playdata.calen.ledger.ai.LedgerAiFeature;
import com.playdata.calen.ledger.ai.LedgerAiFeatureConfig;
import com.playdata.calen.ledger.ai.LedgerAiFeatureSettings;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class AdminOpsControlService {

    private static final String SETTINGS_TABLE = "admin_ops_control_settings";
    private static final String AI_API_KEY_ENCRYPTED = "ai.api-key.encrypted";
    private static final String AI_LMSTUDIO_API_KEY_ENCRYPTED = "ai.lmstudio-api-key.encrypted";
    private static final String AI_OPENAI_API_KEY_ENCRYPTED = "ai.openai-api-key.encrypted";
    private static final String AI_PRESET_SECRET_PREFIX = "ai.preset.";

    private final LedgerAiAnalysisProperties aiProperties;
    private final MinioProperties minioProperties;
    private final MinioBackupArchiveService minioBackupArchiveService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdminOpsSecretCipher adminOpsSecretCipher;
    private volatile String persistenceMessage = "설정 저장소 상태를 아직 확인하지 않았습니다.";

    @PostConstruct
    void initializePersistedSettings() {
        try {
            jdbcTemplate.execute("create table if not exists " + SETTINGS_TABLE + " (setting_key varchar(128) primary key, setting_value varchar(4000), updated_at timestamp default current_timestamp)");
            applyPersistedSettings();
            persistenceMessage = "설정 저장소가 준비되었습니다.";
        } catch (RuntimeException exception) {
            persistenceMessage = "설정 저장소를 준비하지 못했습니다. 변경값은 현재 실행 중인 서버에만 반영될 수 있습니다.";
        }
    }

    public AdminOpsControlResponse getSnapshot() {
        return new AdminOpsControlResponse(
                aiControl(),
                probeAiServer(),
                probeDataServer(),
                persistenceMessage
        );
    }

    @Transactional
    public AdminOpsControlResponse updateAi(AdminAiControlUpdateRequest request) {
        if (request == null) {
            throw new BadRequestException("AI control request is empty.");
        }
        if (hasText(request.targetFeature())) {
            return updateFeatureAi(request, LedgerAiFeature.from(request.targetFeature()));
        }
        if (request.enabled() != null) {
            aiProperties.setEnabled(request.enabled());
        }
        if (hasText(request.provider())) {
            aiProperties.setProvider(normalizeProvider(request.provider()));
        }
        if (request.model() != null) {
            aiProperties.setModel(request.model().trim());
        }
        if (request.workflowUrl() != null) {
            aiProperties.setWorkflowUrl(requireHttpUrl(request.workflowUrl(), "n8n workflow URL"));
        }
        if (request.apiKeyHeader() != null) {
            aiProperties.setApiKeyHeader(defaultText(request.apiKeyHeader(), "X-TravelLedger-AI-Key"));
        }

        if (request.lmStudioBaseUrl() != null) {
            aiProperties.setLmStudioBaseUrl(requireHttpUrl(request.lmStudioBaseUrl(), "LM Studio URL"));
        }
        if (request.lmStudioChatPath() != null) {
            aiProperties.setLmStudioChatPath(normalizePath(request.lmStudioChatPath(), "chat path"));
        }
        if (request.lmStudioModelsPath() != null) {
            aiProperties.setLmStudioModelsPath(normalizePath(request.lmStudioModelsPath(), "models path"));
        }

        if (request.openAiBaseUrl() != null) {
            aiProperties.setOpenAiBaseUrl(requireHttpUrl(request.openAiBaseUrl(), "OpenAI API URL"));
        }
        if (request.openAiChatPath() != null) {
            aiProperties.setOpenAiChatPath(normalizePath(request.openAiChatPath(), "OpenAI chat path"));
        }
        if (request.openAiModelsPath() != null) {
            aiProperties.setOpenAiModelsPath(normalizePath(request.openAiModelsPath(), "OpenAI models path"));
        }
        applyAiSecrets(request);

        if (request.temperature() != null) {
            double value = request.temperature();
            if (value < 0D || value > 2D) {
                throw new BadRequestException("temperature must be between 0 and 2.");
            }
            aiProperties.setTemperature(value);
        }
        if (request.maxTokens() != null) {
            int value = request.maxTokens();
            if (value < 128 || value > 8192) {
                throw new BadRequestException("max tokens must be between 128 and 8192.");
            }
            aiProperties.setMaxTokens(value);
        }
        if (request.connectTimeoutSeconds() != null) {
            aiProperties.setConnectTimeout(seconds(request.connectTimeoutSeconds(), "연결 제한 시간"));
        }
        if (request.readTimeoutSeconds() != null) {
            aiProperties.setReadTimeout(seconds(request.readTimeoutSeconds(), "응답 제한 시간"));
        }
        if (request.enforceProviderUrlAllowlist() != null) {
            aiProperties.setEnforceProviderUrlAllowlist(request.enforceProviderUrlAllowlist());
        }
        if (request.allowedProviderHosts() != null) {
            aiProperties.setAllowedProviderHosts(request.allowedProviderHosts().trim());
        }
        persistRuntimeSettings(request.presetKey());
        return getSnapshot();
    }

    private AdminOpsControlResponse updateFeatureAi(AdminAiControlUpdateRequest request, LedgerAiFeature feature) {
        if (request.enabled() != null) {
            aiProperties.setEnabled(request.enabled());
        }
        applySharedAiSafetySettings(request);
        LedgerAiProvider provider = normalizeFeatureProvider(request.provider());
        LedgerAiFeatureSettings settings = feature == LedgerAiFeature.IMAGE_ANALYSIS
                ? aiProperties.getImage() : aiProperties.getLedger();
        settings.setProvider(provider.name().toLowerCase(java.util.Locale.ROOT));
        if (request.model() != null) {
            settings.setModel(request.model().trim());
        }
        String baseUrl = featureBaseUrl(request, provider);
        if (baseUrl != null) {
            settings.setBaseUrl(requireHttpUrl(baseUrl, providerLabel(provider) + " URL"));
        }
        String chatPath = featureChatPath(request, provider);
        if (chatPath != null) {
            settings.setChatPath(normalizePath(chatPath, "chat path"));
        }
        String modelsPath = featureModelsPath(request, provider);
        if (modelsPath != null) {
            settings.setModelsPath(normalizePath(modelsPath, "models path"));
        }
        if (request.temperature() != null) {
            double value = request.temperature();
            if (value < 0D || value > 2D) {
                throw new BadRequestException("temperature must be between 0 and 2.");
            }
            settings.setTemperature(value);
        }
        if (request.maxTokens() != null) {
            int value = request.maxTokens();
            if (value < 128 || value > 8192) {
                throw new BadRequestException("max tokens must be between 128 and 8192.");
            }
            settings.setMaxTokens(value);
        }
        applyFeatureSecret(request, provider, settings);
        persistRuntimeSettings(request.presetKey());
        return getSnapshot();
    }

    private void applySharedAiSafetySettings(AdminAiControlUpdateRequest request) {
        if (request.connectTimeoutSeconds() != null) {
            aiProperties.setConnectTimeout(seconds(request.connectTimeoutSeconds(), "connection timeout"));
        }
        if (request.readTimeoutSeconds() != null) {
            aiProperties.setReadTimeout(seconds(request.readTimeoutSeconds(), "response timeout"));
        }
        if (request.enforceProviderUrlAllowlist() != null) {
            aiProperties.setEnforceProviderUrlAllowlist(request.enforceProviderUrlAllowlist());
        }
        if (request.allowedProviderHosts() != null) {
            aiProperties.setAllowedProviderHosts(request.allowedProviderHosts().trim());
        }
    }

    private LedgerAiProvider normalizeFeatureProvider(String value) {
        LedgerAiProvider provider = LedgerAiProvider.from(value);
        if (provider == LedgerAiProvider.N8N) {
            throw new BadRequestException("AI feature provider must be OpenAI API, LM Studio, or Ollama.");
        }
        return provider;
    }

    private String featureBaseUrl(AdminAiControlUpdateRequest request, LedgerAiProvider provider) {
        return switch (provider) {
            case OPENAI -> request.openAiBaseUrl();
            case LMSTUDIO -> request.lmStudioBaseUrl();
            case OLLAMA -> request.ollamaBaseUrl();
            case N8N -> null;
        };
    }

    private String featureChatPath(AdminAiControlUpdateRequest request, LedgerAiProvider provider) {
        return switch (provider) {
            case OPENAI -> request.openAiChatPath();
            case LMSTUDIO -> request.lmStudioChatPath();
            case OLLAMA -> request.ollamaChatPath();
            case N8N -> null;
        };
    }

    private String featureModelsPath(AdminAiControlUpdateRequest request, LedgerAiProvider provider) {
        return switch (provider) {
            case OPENAI -> request.openAiModelsPath();
            case LMSTUDIO -> request.lmStudioModelsPath();
            case OLLAMA -> request.ollamaModelsPath();
            case N8N -> null;
        };
    }

    private void applyFeatureSecret(AdminAiControlUpdateRequest request, LedgerAiProvider provider, LedgerAiFeatureSettings settings) {
        Boolean clear = switch (provider) {
            case OPENAI -> request.clearOpenAiApiKey();
            case LMSTUDIO -> request.clearLmStudioApiKey();
            case OLLAMA -> request.clearOllamaApiKey();
            case N8N -> false;
        };
        String supplied = switch (provider) {
            case OPENAI -> request.openAiApiKey();
            case LMSTUDIO -> request.lmStudioApiKey();
            case OLLAMA -> request.ollamaApiKey();
            case N8N -> "";
        };
        if (Boolean.TRUE.equals(clear)) {
            settings.setApiKey("");
        } else if (hasText(supplied)) {
            settings.setApiKey(supplied.trim());
        }
    }

    private String providerLabel(LedgerAiProvider provider) {
        return switch (provider) {
            case OPENAI -> "OpenAI API";
            case LMSTUDIO -> "LM Studio";
            case OLLAMA -> "Ollama";
            case N8N -> "n8n";
        };
    }
    @Transactional
    public AdminOpsControlResponse updateDataStorage(AdminDataStorageControlUpdateRequest request) {
        if (request == null || request.minioStorageCapacityBytes() == null) {
            throw new BadRequestException("MinIO storage capacity is required.");
        }
        long capacityBytes = request.minioStorageCapacityBytes();
        if (capacityBytes < 0L) {
            throw new BadRequestException("MinIO storage capacity must be 0 or greater.");
        }
        if (capacityBytes > 1_125_899_906_842_624L) {
            throw new BadRequestException("MinIO storage capacity must be 1 PB or less.");
        }
        minioProperties.setStorageCapacityBytes(capacityBytes);
        persistRuntimeSettings();
        return getSnapshot();
    }

    private void applyAiSecrets(AdminAiControlUpdateRequest request) {
        String presetKey = normalizePresetKey(request.presetKey());
        applySecret(request.clearApiKey(), request.apiKey(), request.reuseExistingSecrets(), presetKey, "api-key", aiProperties::setApiKey);
        applySecret(request.clearLmStudioApiKey(), request.lmStudioApiKey(), request.reuseExistingSecrets(), presetKey, "lmstudio-api-key", aiProperties::setLmStudioApiKey);
        applySecret(request.clearOpenAiApiKey(), request.openAiApiKey(), request.reuseExistingSecrets(), presetKey, "openai-api-key", aiProperties::setOpenAiApiKey);
    }

    private void applySecret(
            Boolean clearRequested,
            String suppliedValue,
            Boolean reuseExistingSecret,
            String presetKey,
            String secretType,
            java.util.function.Consumer<String> setter
    ) {
        if (Boolean.TRUE.equals(clearRequested)) {
            setter.accept("");
            return;
        }
        if (hasText(suppliedValue)) {
            setter.accept(suppliedValue.trim());
            return;
        }
        if (Boolean.TRUE.equals(reuseExistingSecret)) {
            return;
        }
        if (presetKey != null) {
            setter.accept(readPresetSecret(presetKey, secretType).orElse(""));
        }
    }

    private Optional<String> readPresetSecret(String presetKey, String secretType) {
        return readEncryptedSetting(presetSecretSettingKey(presetKey, secretType));
    }

    private void persistPresetSecrets(String rawPresetKey) {
        String presetKey = normalizePresetKey(rawPresetKey);
        if (presetKey == null) {
            return;
        }
        persistEncryptedSetting(presetSecretSettingKey(presetKey, "api-key"), aiProperties.getApiKey());
        persistEncryptedSetting(presetSecretSettingKey(presetKey, "lmstudio-api-key"), aiProperties.getLmStudioApiKey());
        persistEncryptedSetting(presetSecretSettingKey(presetKey, "openai-api-key"), aiProperties.getOpenAiApiKey());
    }

    public void deletePresetSecrets(String rawPresetKey) {
        String presetKey = normalizePresetKey(rawPresetKey);
        if (presetKey == null) {
            return;
        }
        deleteSetting(presetSecretSettingKey(presetKey, "api-key"));
        deleteSetting(presetSecretSettingKey(presetKey, "lmstudio-api-key"));
        deleteSetting(presetSecretSettingKey(presetKey, "openai-api-key"));
    }

    private void restoreEncryptedSetting(Map<String, String> settings, String settingKey, java.util.function.Consumer<String> setter) {
        String encrypted = settings.get(settingKey);
        if (encrypted == null || encrypted.isBlank()) {
            return;
        }
        adminOpsSecretCipher.decrypt(encrypted).ifPresent(setter);
    }

    private Optional<String> readEncryptedSetting(String settingKey) {
        try {
            String encrypted = jdbcTemplate.query(
                    "select setting_value from " + SETTINGS_TABLE + " where setting_key = ?",
                    resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                    settingKey
            );
            return adminOpsSecretCipher.decrypt(encrypted);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void persistEncryptedSetting(String settingKey, String plaintext) {
        if (!hasText(plaintext)) {
            deleteSetting(settingKey);
            return;
        }
        persistSetting(settingKey, adminOpsSecretCipher.encrypt(plaintext.trim()));
    }

    private void deleteSetting(String settingKey) {
        jdbcTemplate.update("delete from " + SETTINGS_TABLE + " where setting_key = ?", settingKey);
    }

    private String normalizePresetKey(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 2048) {
            throw new BadRequestException("AI server preset key is too long.");
        }
        return normalized;
    }

    private String presetSecretSettingKey(String presetKey, String secretType) {
        return AI_PRESET_SECRET_PREFIX + sha256(presetKey) + "." + secretType;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
    private AdminAiControlResponse aiControl() {
        return new AdminAiControlResponse(
                aiProperties.isEnabled(),
                aiProperties.getProvider(),
                aiProperties.getModel(),
                aiProperties.getWorkflowUrl(),
                aiProperties.getApiKeyHeader(),
                aiProperties.isApiKeyConfigured(),
                aiProperties.getLmStudioBaseUrl(),
                aiProperties.normalizedLmStudioChatPath(),
                aiProperties.normalizedLmStudioModelsPath(),
                hasText(aiProperties.getLmStudioApiKey()),
                aiProperties.getOpenAiBaseUrl(),
                aiProperties.normalizedOpenAiChatPath(),
                aiProperties.normalizedOpenAiModelsPath(),
                hasText(aiProperties.getOpenAiApiKey()),
                aiProperties.getTemperature(),
                aiProperties.getMaxTokens(),
                Math.max(0L, aiProperties.getConnectTimeout().toSeconds()),
                Math.max(0L, aiProperties.getReadTimeout().toSeconds()),
                aiProperties.isEnforceProviderUrlAllowlist(),
                aiProperties.getAllowedProviderHosts(),
                aiProperties.isConfigured(),
                aiProperties.statusMessage(),
                featureAiControl(LedgerAiFeature.LEDGER_ANALYSIS),
                featureAiControl(LedgerAiFeature.IMAGE_ANALYSIS)
        );
    }

    private AdminAiFeatureConfigResponse featureAiControl(LedgerAiFeature feature) {
        LedgerAiFeatureConfig config = aiProperties.featureConfig(feature);
        boolean configured = aiProperties.isFeatureConfigured(feature);
        String featureLabel = feature == LedgerAiFeature.IMAGE_ANALYSIS ? "이미지 분석" : "가계부 AI 분석";
        String message = configured
                ? featureLabel + " 서버 설정이 준비되었습니다."
                : featureLabel + " 서버의 주소, 모델, API 키(필요한 경우)를 확인하세요.";
        return new AdminAiFeatureConfigResponse(
                feature.settingKey(),
                config.provider().name().toLowerCase(java.util.Locale.ROOT),
                config.model(),
                config.baseUrl(),
                config.chatPath(),
                config.modelsPath(),
                hasText(config.apiKey()),
                config.temperature(),
                config.maxTokens(),
                configured,
                message
        );
    }

    private AdminAiServerStatusResponse probeAiServer() {
        long started = System.nanoTime();
        LedgerAiFeature feature = LedgerAiFeature.LEDGER_ANALYSIS;
        LedgerAiFeatureConfig config = aiProperties.featureConfig(feature);
        String provider = config.provider().name().toLowerCase(java.util.Locale.ROOT);
        if (!aiProperties.isEnabled()) {
            return new AdminAiServerStatusResponse(false, provider, config.baseUrl(), config.modelsPath(), 0L, List.of(), "AI is disabled.");
        }
        if (!aiProperties.isFeatureConfigured(feature)) {
            String message = config.provider() == LedgerAiProvider.OPENAI && !hasText(config.apiKey())
                    ? "OpenAI API key is not configured."
                    : "Ledger AI analysis server settings are incomplete or blocked by the provider host allowlist.";
            return new AdminAiServerStatusResponse(false, provider, config.baseUrl(), config.modelsPath(), 0L, List.of(), message);
        }

        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(aiProperties.getConnectTimeout());
            requestFactory.setReadTimeout(Duration.ofSeconds(Math.min(Math.max(aiProperties.getReadTimeout().toSeconds(), 3L), 15L)));
            RestClient client = RestClient.builder()
                    .baseUrl(config.baseUrl())
                    .requestFactory(requestFactory)
                    .build();
            RestClient.RequestHeadersSpec<?> request = client.get()
                    .uri(config.modelsPath())
                    .accept(MediaType.APPLICATION_JSON);
            if (hasText(config.apiKey())) {
                request = request.header("Authorization", "Bearer " + config.apiKey());
            }
            String body = request.retrieve().body(String.class);
            List<String> models = extractModels(body);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            return new AdminAiServerStatusResponse(true, provider, config.baseUrl(), config.modelsPath(), elapsedMillis, models,
                    models.isEmpty() ? "Ledger AI server responded, but model list is empty." : "Ledger AI server responded normally.");
        } catch (RuntimeException exception) {
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            return new AdminAiServerStatusResponse(false, provider, config.baseUrl(), config.modelsPath(), elapsedMillis, List.of(), safeMessage(exception));
        }
    }
    private AdminAiServerStatusResponse probeN8nStatus(long started) {
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        if (!hasText(aiProperties.getWorkflowUrl())) {
            return new AdminAiServerStatusResponse(false, aiProperties.getProvider(), aiProperties.getWorkflowUrl(), "webhook", elapsedMillis, List.of(), "n8n workflow URL is not configured.");
        }
        if (!aiProperties.isWorkflowConfigured()) {
            return new AdminAiServerStatusResponse(false, aiProperties.getProvider(), aiProperties.getWorkflowUrl(), "webhook", elapsedMillis, List.of(), aiProperties.statusMessage());
        }
        if (!aiProperties.isApiKeyConfigured()) {
            return new AdminAiServerStatusResponse(false, aiProperties.getProvider(), aiProperties.getWorkflowUrl(), "webhook", elapsedMillis, List.of(), "n8n API key is not configured.");
        }
        return new AdminAiServerStatusResponse(true, aiProperties.getProvider(), aiProperties.getWorkflowUrl(), "webhook", elapsedMillis, List.of(), "n8n workflow is configured. Live probe is skipped to avoid triggering analysis.");
    }

    private AdminDataServerStatusResponse probeDataServer() {
        boolean reachable = false;
        String product = "-";
        String message = "Database connection was not checked.";
        try {
            Integer one = jdbcTemplate.queryForObject("select 1", Integer.class);
            reachable = one != null && one == 1;
            product = jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
            message = reachable ? "Database connection is healthy." : "Database response was invalid.";
        } catch (Exception exception) {
            message = safeMessage(exception);
        }
        AdminMinioStorageSummaryResponse storage = minioBackupArchiveService.getSummary();
        return new AdminDataServerStatusResponse(reachable, product, databaseHost(), message, storage);
    }

    private String databaseHost() {
        try {
            String url = jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
            if (!hasText(url)) {
                return "-";
            }
            int marker = url.indexOf("://");
            if (marker < 0) {
                return "-";
            }
            String rest = url.substring(marker + 3);
            int slash = rest.indexOf('/');
            return slash >= 0 ? rest.substring(0, slash) : rest;
        } catch (Exception exception) {
            return "-";
        }
    }

    private List<String> extractModels(String body) {
        if (!hasText(body)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            List<String> models = new ArrayList<>();
            collectModels(root.path("data"), models);
            collectModels(root.path("models"), models);
            collectModels(root, models);
            return models.stream().distinct().limit(12).toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    private void collectModels(JsonNode node, List<String> models) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                addModel(item, models);
            }
            return;
        }
        addModel(node, models);
    }

    private void addModel(JsonNode node, List<String> models) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual() && hasText(node.asText())) {
            models.add(node.asText());
            return;
        }
        for (String field : List.of("id", "model", "key", "name", "path")) {
            String value = node.path(field).asText("");
            if (hasText(value)) {
                models.add(value);
                return;
            }
        }
    }

    private void applyPersistedSettings() {
        Map<String, String> settings = jdbcTemplate.query("select setting_key, setting_value from " + SETTINGS_TABLE, resultSet -> {
            Map<String, String> result = new LinkedHashMap<>();
            while (resultSet.next()) {
                result.put(resultSet.getString("setting_key"), resultSet.getString("setting_value"));
            }
            return result;
        });
        applyBoolean(settings, "ai.enabled", aiProperties::setEnabled);
        applyText(settings, "ai.provider", value -> aiProperties.setProvider(normalizeProvider(value)));
        applyText(settings, "ai.model", aiProperties::setModel);
        applyText(settings, "ai.workflow-url", aiProperties::setWorkflowUrl);
        applyText(settings, "ai.api-key-header", aiProperties::setApiKeyHeader);
        applyText(settings, "ai.lmstudio-base-url", aiProperties::setLmStudioBaseUrl);
        applyText(settings, "ai.lmstudio-chat-path", aiProperties::setLmStudioChatPath);
        applyText(settings, "ai.lmstudio-models-path", aiProperties::setLmStudioModelsPath);
        applyText(settings, "ai.openai-base-url", aiProperties::setOpenAiBaseUrl);
        applyText(settings, "ai.openai-chat-path", aiProperties::setOpenAiChatPath);
        applyText(settings, "ai.openai-models-path", aiProperties::setOpenAiModelsPath);
        applyDouble(settings, "ai.temperature", aiProperties::setTemperature);
        applyInteger(settings, "ai.max-tokens", aiProperties::setMaxTokens);
        applyDurationSeconds(settings, "ai.connect-timeout-seconds", aiProperties::setConnectTimeout);
        applyDurationSeconds(settings, "ai.read-timeout-seconds", aiProperties::setReadTimeout);
        applyBoolean(settings, "ai.enforce-provider-url-allowlist", aiProperties::setEnforceProviderUrlAllowlist);
        applyText(settings, "ai.allowed-provider-hosts", aiProperties::setAllowedProviderHosts);
        restoreEncryptedSetting(settings, AI_API_KEY_ENCRYPTED, aiProperties::setApiKey);
        restoreEncryptedSetting(settings, AI_LMSTUDIO_API_KEY_ENCRYPTED, aiProperties::setLmStudioApiKey);
        restoreEncryptedSetting(settings, AI_OPENAI_API_KEY_ENCRYPTED, aiProperties::setOpenAiApiKey);
        restoreFeatureSettings(settings, LedgerAiFeature.LEDGER_ANALYSIS, aiProperties.getLedger());
        restoreFeatureSettings(settings, LedgerAiFeature.IMAGE_ANALYSIS, aiProperties.getImage());
        applyLong(settings, "minio.storage-capacity-bytes", minioProperties::setStorageCapacityBytes);
    }

    private void restoreFeatureSettings(Map<String, String> settings, LedgerAiFeature feature, LedgerAiFeatureSettings target) {
        String prefix = featureSettingPrefix(feature);
        applyText(settings, prefix + "provider", target::setProvider);
        applyText(settings, prefix + "model", target::setModel);
        applyText(settings, prefix + "base-url", target::setBaseUrl);
        applyText(settings, prefix + "chat-path", target::setChatPath);
        applyText(settings, prefix + "models-path", target::setModelsPath);
        applyDouble(settings, prefix + "temperature", target::setTemperature);
        applyInteger(settings, prefix + "max-tokens", target::setMaxTokens);
        restoreEncryptedSetting(settings, prefix + "api-key.encrypted", target::setApiKey);
    }

    private void persistFeatureSettings(LedgerAiFeature feature, LedgerAiFeatureSettings source) {
        String prefix = featureSettingPrefix(feature);
        persistSetting(prefix + "provider", source.getProvider());
        persistSetting(prefix + "model", source.getModel());
        persistSetting(prefix + "base-url", source.getBaseUrl());
        persistSetting(prefix + "chat-path", source.getChatPath());
        persistSetting(prefix + "models-path", source.getModelsPath());
        persistSetting(prefix + "temperature", source.getTemperature() == null ? "" : Double.toString(source.getTemperature()));
        persistSetting(prefix + "max-tokens", source.getMaxTokens() == null ? "" : Integer.toString(source.getMaxTokens()));
        persistEncryptedSetting(prefix + "api-key.encrypted", source.getApiKey());
    }

    private String featureSettingPrefix(LedgerAiFeature feature) {
        return "ai.feature." + feature.settingKey() + ".";
    }
    private void persistRuntimeSettings() {
        persistRuntimeSettings(null);
    }

    private void persistRuntimeSettings(String presetKey) {
        try {
            persistSetting("ai.enabled", Boolean.toString(aiProperties.isEnabled()));
            persistSetting("ai.provider", aiProperties.getProvider());
            persistSetting("ai.model", aiProperties.getModel());
            persistSetting("ai.workflow-url", aiProperties.getWorkflowUrl());
            persistSetting("ai.api-key-header", aiProperties.getApiKeyHeader());
            persistSetting("ai.lmstudio-base-url", aiProperties.getLmStudioBaseUrl());
            persistSetting("ai.lmstudio-chat-path", aiProperties.getLmStudioChatPath());
            persistSetting("ai.lmstudio-models-path", aiProperties.getLmStudioModelsPath());
            persistSetting("ai.openai-base-url", aiProperties.getOpenAiBaseUrl());
            persistSetting("ai.openai-chat-path", aiProperties.getOpenAiChatPath());
            persistSetting("ai.openai-models-path", aiProperties.getOpenAiModelsPath());
            persistSetting("ai.temperature", Double.toString(aiProperties.getTemperature()));
            persistSetting("ai.max-tokens", Integer.toString(aiProperties.getMaxTokens()));
            persistSetting("ai.connect-timeout-seconds", Long.toString(aiProperties.getConnectTimeout().toSeconds()));
            persistSetting("ai.read-timeout-seconds", Long.toString(aiProperties.getReadTimeout().toSeconds()));
            persistSetting("ai.enforce-provider-url-allowlist", Boolean.toString(aiProperties.isEnforceProviderUrlAllowlist()));
            persistSetting("ai.allowed-provider-hosts", aiProperties.getAllowedProviderHosts());
            persistEncryptedSetting(AI_API_KEY_ENCRYPTED, aiProperties.getApiKey());
            persistEncryptedSetting(AI_LMSTUDIO_API_KEY_ENCRYPTED, aiProperties.getLmStudioApiKey());
            persistEncryptedSetting(AI_OPENAI_API_KEY_ENCRYPTED, aiProperties.getOpenAiApiKey());
            persistFeatureSettings(LedgerAiFeature.LEDGER_ANALYSIS, aiProperties.getLedger());
            persistFeatureSettings(LedgerAiFeature.IMAGE_ANALYSIS, aiProperties.getImage());
            persistPresetSecrets(presetKey);
            persistSetting("minio.storage-capacity-bytes", Long.toString(minioProperties.getStorageCapacityBytes()));
            persistenceMessage = "설정이 저장되었습니다.";
        } catch (RuntimeException exception) {
            persistenceMessage = "설정 저장소에 저장하지 못했습니다. 변경값은 현재 실행 중인 서버에만 반영되었습니다.";
        }
    }

    private void persistSetting(String key, String value) {
        String safeValue = value == null ? "" : value;
        int updated = jdbcTemplate.update("update " + SETTINGS_TABLE + " set setting_value = ?, updated_at = current_timestamp where setting_key = ?", safeValue, key);
        if (updated == 0) {
            try {
                jdbcTemplate.update("insert into " + SETTINGS_TABLE + " (setting_key, setting_value, updated_at) values (?, ?, current_timestamp)", key, safeValue);
            } catch (RuntimeException exception) {
                jdbcTemplate.update("update " + SETTINGS_TABLE + " set setting_value = ?, updated_at = current_timestamp where setting_key = ?", safeValue, key);
            }
        }
    }

    private void applyText(Map<String, String> settings, String key, java.util.function.Consumer<String> setter) {
        if (settings.containsKey(key)) {
            try {
                setter.accept(settings.get(key));
            } catch (RuntimeException exception) {
                // Ignore invalid persisted setting.
            }
        }
    }

    private void applyBoolean(Map<String, String> settings, String key, java.util.function.Consumer<Boolean> setter) {
        if (settings.containsKey(key)) {
            setter.accept(Boolean.parseBoolean(settings.get(key)));
        }
    }

    private void applyInteger(Map<String, String> settings, String key, java.util.function.IntConsumer setter) {
        if (settings.containsKey(key)) {
            try {
                setter.accept(Integer.parseInt(settings.get(key)));
            } catch (NumberFormatException exception) {
                // Ignore invalid persisted setting.
            }
        }
    }

    private void applyLong(Map<String, String> settings, String key, java.util.function.LongConsumer setter) {
        if (settings.containsKey(key)) {
            try {
                setter.accept(Long.parseLong(settings.get(key)));
            } catch (NumberFormatException exception) {
                // Ignore invalid persisted setting.
            }
        }
    }

    private void applyDouble(Map<String, String> settings, String key, java.util.function.DoubleConsumer setter) {
        if (settings.containsKey(key)) {
            try {
                setter.accept(Double.parseDouble(settings.get(key)));
            } catch (NumberFormatException exception) {
                // Ignore invalid persisted setting.
            }
        }
    }

    private void applyDurationSeconds(Map<String, String> settings, String key, java.util.function.Consumer<Duration> setter) {
        if (settings.containsKey(key)) {
            try {
                long seconds = Long.parseLong(settings.get(key));
                if (seconds > 0L) {
                    setter.accept(Duration.ofSeconds(seconds));
                }
            } catch (NumberFormatException exception) {
                // Ignore invalid persisted setting.
            }
        }
    }

    private String requireHttpUrl(String raw, String label) {
        String value = raw == null ? "" : raw.trim();
        if (!hasText(value)) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && hasText(uri.getHost())) {
                return value;
            }
        } catch (IllegalArgumentException exception) {
            // handled below
        }
        throw new BadRequestException(label + " must be a valid http(s) URL.");
    }

    private String normalizePath(String raw, String label) {
        String value = raw == null ? "" : raw.trim();
        if (!value.startsWith("/")) {
            throw new BadRequestException(label + " must start with /.");
        }
        return value;
    }

    private Duration seconds(long value, String label) {
        if (value < 1L || value > 600L) {
            throw new BadRequestException(label + " must be between 1 and 600 seconds.");
        }
        return Duration.ofSeconds(value);
    }

    private String safeMessage(Exception exception) {
        String message = exception == null ? null : exception.getMessage();
        if (!hasText(message)) {
            return "Status check failed.";
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    private String normalizeProvider(String value) {
        String provider = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (provider) {
            case "lmstudio", "lm-studio", "lm_studio", "openai-compatible" -> "lmstudio";
            case "openai", "openai-api", "chatgpt" -> "openai";
            case "ollama" -> "ollama";
            case "n8n" -> "n8n";
            default -> throw new BadRequestException("AI provider must be lmstudio, openai, ollama, or n8n.");
        };
    }
    private String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
