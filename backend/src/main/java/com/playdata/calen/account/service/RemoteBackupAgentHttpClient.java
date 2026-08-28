package com.playdata.calen.account.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.playdata.calen.account.dto.AdminBackupFileResponse;
import com.playdata.calen.common.exception.BadRequestException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class RemoteBackupAgentHttpClient implements RemoteBackupAgentClient {

    private static final String MARIA_DB_FILE_PATTERN = "[A-Za-z0-9._-]+\\.sql\\.gz";
    private static final String MINIO_FILE_PATTERN = "[A-Za-z0-9._-]+\\.zip";

    private final RestClient restClient;
    private final String agentUrl;
    private final String agentToken;

    @Autowired
    public RemoteBackupAgentHttpClient(
            @Value("${app.data-ops.agent-url:}") String agentUrl,
            @Value("${app.data-ops.agent-token:}") String agentToken,
            @Value("${app.data-ops.agent-connect-timeout:5s}") Duration connectTimeout,
            @Value("${app.data-ops.agent-read-timeout:60s}") Duration readTimeout
    ) {
        this(createProductionRestClient(connectTimeout, readTimeout), agentUrl, agentToken);
    }

    RemoteBackupAgentHttpClient(RestClient restClient, String agentUrl, String agentToken) {
        this.restClient = Objects.requireNonNull(restClient);
        this.agentUrl = normalizeAgentUrl(agentUrl);
        this.agentToken = agentToken == null ? "" : agentToken;
    }

    private static RestClient createProductionRestClient(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public AdminBackupFileResponse createMariaDbBackup() {
        ensureConfigured();
        return toAdminResponse(
                restClient.post()
                        .uri(agentUrl + "/v1/backups/mariadb")
                        .headers(headers -> headers.setBearerAuth(agentToken))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, this::raiseRemoteError)
                        .body(RemoteBackupFileResponse.class),
                MARIA_DB_FILE_PATTERN
        );
    }

    @Override
    public AdminBackupFileResponse createMinioBackup() {
        ensureConfigured();
        return toAdminResponse(
                restClient.post()
                        .uri(agentUrl + "/v1/backups/minio")
                        .headers(headers -> headers.setBearerAuth(agentToken))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, this::raiseRemoteError)
                        .body(RemoteBackupFileResponse.class),
                MINIO_FILE_PATTERN
        );
    }

    @Override
    public List<AdminBackupFileResponse> listBackups(String service) {
        ensureConfigured();
        String filePattern = switch (service) {
            case "mariadb" -> MARIA_DB_FILE_PATTERN;
            case "minio" -> MINIO_FILE_PATTERN;
            default -> throw new BadRequestException("원격 백업 서비스가 올바르지 않습니다.");
        };
        RemoteBackupListResponse response = restClient.get()
                .uri(agentUrl + "/v1/backups?service=" + service)
                .headers(headers -> headers.setBearerAuth(agentToken))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raiseRemoteError)
                .body(RemoteBackupListResponse.class);
        if (response == null || response.backups() == null) {
            return List.of();
        }
        return response.backups().stream()
                .map(file -> toAdminResponse(file, filePattern))
                .toList();
    }

    @Override
    public void restoreMariaDb(String fileName) {
        validateFileName(fileName, MARIA_DB_FILE_PATTERN);
        ensureConfigured();
        restClient.post()
                .uri(agentUrl + "/v1/restores/mariadb")
                .headers(headers -> headers.setBearerAuth(agentToken))
                .body(new RestoreRequest(fileName))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::raiseRemoteError)
                .toBodilessEntity();
    }

    private void ensureConfigured() {
        if (!StringUtils.hasText(agentUrl) || !StringUtils.hasText(agentToken)) {
            throw new BadRequestException("원격 백업 에이전트 설정이 완료되지 않았습니다.");
        }
    }

    private void raiseRemoteError(org.springframework.http.HttpRequest request,
                                  org.springframework.http.client.ClientHttpResponse response)
            throws java.io.IOException {
        throw new BadRequestException(
                "원격 백업 에이전트 요청이 실패했습니다. HTTP " + response.getStatusCode().value()
        );
    }

    private AdminBackupFileResponse toAdminResponse(RemoteBackupFileResponse response, String pattern) {
        if (response == null) {
            throw new BadRequestException("원격 백업 에이전트 응답이 비어 있습니다.");
        }
        return toAdminResponse(
                new AdminBackupFileResponse(response.fileName(), response.sizeBytes(), response.modifiedAt()),
                pattern
        );
    }

    private AdminBackupFileResponse toAdminResponse(AdminBackupFileResponse response, String pattern) {
        if (response == null || response.fileName() == null || !response.fileName().matches(pattern)
                || response.sizeBytes() < 0) {
            throw new BadRequestException("원격 백업 에이전트 응답 형식이 올바르지 않습니다.");
        }
        return response;
    }

    private void validateFileName(String fileName, String pattern) {
        if (fileName == null || !fileName.matches(pattern)) {
            throw new BadRequestException("복구할 백업 파일 이름이 올바르지 않습니다.");
        }
    }

    private static String normalizeAgentUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("원격 백업 에이전트 URL 설정이 올바르지 않습니다.");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || !StringUtils.hasText(uri.getHost())
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException("원격 백업 에이전트 URL 설정이 올바르지 않습니다.");
        }
        return normalized.replaceAll("/+$", "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RemoteBackupFileResponse(String fileName, long sizeBytes, String modifiedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RemoteBackupListResponse(List<RemoteBackupFileResponse> backups) {
    }

    record RestoreRequest(String fileName) {
    }
}
