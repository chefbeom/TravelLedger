package com.playdata.calen.account.social;

import com.playdata.calen.account.security.AuthEndpointPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class KakaoOAuthClient {

    private final RestClient restClient;
    private final KakaoOAuthProperties properties;

    @Autowired
    public KakaoOAuthClient(KakaoOAuthProperties properties) {
        this(createRestClient(properties.getConnectTimeout(), properties.getReadTimeout()), properties);
    }

    KakaoOAuthClient(RestClient restClient, KakaoOAuthProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public boolean isUnlinkConfigured() {
        return StringUtils.hasText(properties.getAdminKey()) && AuthEndpointPolicy.isSafeUrl(properties.getUnlinkUri(), true);
    }

    /** Called from an idempotent withdrawal workflow; a failure must never be treated as success. */
    public void unlinkAccount(String providerUserId) {
        if (!isUnlinkConfigured()) {
            throw new ServiceUnavailableException("카카오 연결 해제를 위한 서버 설정이 필요합니다.");
        }
        String subject = providerUserId == null ? "" : providerUserId.trim();
        if (!subject.matches("[1-9][0-9]{0,18}")) {
            throw new BadRequestException("카카오 연결 정보를 확인할 수 없습니다.");
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("target_id_type", "user_id");
            form.add("target_id", subject);
            JsonNode result = restClient.post().uri(properties.getUnlinkUri().trim())
                    .header("Authorization", "KakaoAK " + properties.getAdminKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
            if (result == null || !subject.equals(result.path("id").asText(""))) {
                throw new ServiceUnavailableException("카카오 연결 해제 결과를 확인할 수 없습니다.");
            }
        } catch (RestClientException exception) {
            throw new ServiceUnavailableException("카카오 연결 해제 서버와 통신할 수 없습니다.");
        }
    }

    public String authorizationUrl(String state) {
        ensureConfigured();
        if (!StringUtils.hasText(state)) {
            throw new BadRequestException("OAuth 상태값이 없습니다.");
        }
        return UriComponentsBuilder.fromUriString(properties.getAuthorizationUri().trim())
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri().trim())
                .queryParam("state", state)
                .queryParam("scope", "account_email,profile_nickname")
                .build()
                .encode()
                .toUriString();
    }

    public KakaoIdentity fetchIdentity(String authorizationCode) {
        ensureConfigured();
        if (!StringUtils.hasText(authorizationCode)) {
            throw new BadRequestException("카카오 인증 코드가 없습니다.");
        }

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "authorization_code");
            form.add("client_id", properties.getClientId());
            form.add("redirect_uri", properties.getRedirectUri().trim());
            form.add("code", authorizationCode);
            if (StringUtils.hasText(properties.getClientSecret())) {
                form.add("client_secret", properties.getClientSecret());
            }

            JsonNode token = restClient.post()
                    .uri(properties.getTokenUri().trim())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            String accessToken = token == null ? "" : token.path("access_token").asText("");
            if (!StringUtils.hasText(accessToken)) {
                throw new ServiceUnavailableException("카카오 인증 토큰을 받을 수 없습니다.");
            }

            JsonNode profile = restClient.get()
                    .uri(properties.getUserInfoUri().trim())
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .body(JsonNode.class);
            KakaoIdentity identity = toIdentity(profile);
            if (properties.getExpectedAppId() == null) return identity;
            JsonNode tokenInfo = restClient.get()
                    .uri(properties.getTokenInfoUri().trim())
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .body(JsonNode.class);
            if (tokenInfo == null || !tokenInfo.path("app_id").isIntegralNumber()
                    || !tokenInfo.path("app_id").canConvertToLong()
                    || !properties.getExpectedAppId().equals(tokenInfo.path("app_id").longValue())
                    || !tokenInfo.path("id").isIntegralNumber() || !tokenInfo.path("id").canConvertToLong()
                    || tokenInfo.path("id").longValue() <= 0
                    || !tokenInfo.path("expires_in").isIntegralNumber() || !tokenInfo.path("expires_in").canConvertToLong()
                    || tokenInfo.path("expires_in").longValue() <= 0
                    || !identity.providerUserId().equals(tokenInfo.path("id").asText(""))) {
                throw new ServiceUnavailableException("카카오 계정과 앱 정보를 확인할 수 없습니다.");
            }
            return new KakaoIdentity(identity.providerUserId(), identity.email(), identity.displayName(),
                    identity.emailVerified(), properties.getExpectedAppId());
        } catch (RestClientException exception) {
            throw new ServiceUnavailableException("카카오 로그인 서버와 통신할 수 없습니다.");
        }
    }

    private KakaoIdentity toIdentity(JsonNode profile) {
        if (profile == null || !StringUtils.hasText(profile.path("id").asText(""))
                || profile.path("id").asText().length() > 128) {
            throw new ServiceUnavailableException("카카오 계정 정보를 확인할 수 없습니다.");
        }
        JsonNode account = profile.path("kakao_account");
        String email = account.path("email").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        boolean emailValid = account.path("is_email_valid").asBoolean(false);
        boolean emailVerified = account.path("is_email_verified").asBoolean(false);
        if (!emailValid || !emailVerified || !email.contains("@")) {
            throw new BadRequestException("카카오 계정의 인증된 이메일이 필요합니다.");
        }
        String displayName = profile.path("properties").path("nickname").asText("").trim();
        if (!StringUtils.hasText(displayName)) displayName = account.path("profile").path("nickname").asText("").trim();
        return new KakaoIdentity(profile.path("id").asText(), email, displayName, true);
    }

    private void ensureConfigured() {
        if (!properties.isConfigured()) {
            throw new ServiceUnavailableException("카카오 로그인이 아직 설정되지 않았습니다.");
        }
    }

    private static RestClient createRestClient(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout != null ? connectTimeout : Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout != null ? readTimeout : Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
