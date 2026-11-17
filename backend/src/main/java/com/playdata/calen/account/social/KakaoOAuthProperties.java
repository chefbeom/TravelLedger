package com.playdata.calen.account.social;

import com.playdata.calen.account.security.AuthEndpointPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "app.auth.kakao")
public class KakaoOAuthProperties {

    private boolean enabled;
    private String clientId = "";
    private String clientSecret = "";
    private String adminKey = "";
    private Long expectedAppId;
    // Operator attestation only: this does not itself prove which app owns the admin key.
    private Long adminAppId;
    private String redirectUri = "";
    private String frontendBaseUrl = "";
    private String authorizationUri = "https://kauth.kakao.com/oauth/authorize";
    private String tokenUri = "https://kauth.kakao.com/oauth/token";
    private String userInfoUri = "https://kapi.kakao.com/v2/user/me";
    private String tokenInfoUri = "https://kapi.kakao.com/v1/user/access_token_info";
    private String unlinkUri = "https://kapi.kakao.com/v1/user/unlink";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getAdminKey() { return adminKey; }
    public void setAdminKey(String adminKey) { this.adminKey = adminKey; }
    public Long getExpectedAppId() { return expectedAppId; }
    public void setExpectedAppId(Long expectedAppId) { this.expectedAppId = expectedAppId; }
    public Long getAdminAppId() { return adminAppId; }
    public void setAdminAppId(Long adminAppId) { this.adminAppId = adminAppId; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }
    public String getFrontendBaseUrl() { return frontendBaseUrl; }
    public void setFrontendBaseUrl(String frontendBaseUrl) { this.frontendBaseUrl = frontendBaseUrl; }
    public String getAuthorizationUri() { return authorizationUri; }
    public void setAuthorizationUri(String authorizationUri) { this.authorizationUri = authorizationUri; }
    public String getTokenUri() { return tokenUri; }
    public void setTokenUri(String tokenUri) { this.tokenUri = tokenUri; }
    public String getUserInfoUri() { return userInfoUri; }
    public void setUserInfoUri(String userInfoUri) { this.userInfoUri = userInfoUri; }
    public String getTokenInfoUri() { return tokenInfoUri; }
    public void setTokenInfoUri(String tokenInfoUri) { this.tokenInfoUri = tokenInfoUri; }
    public String getUnlinkUri() { return unlinkUri; }
    public void setUnlinkUri(String unlinkUri) { this.unlinkUri = unlinkUri; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

    public boolean isConfigured() {
        return enabled
                && StringUtils.hasText(clientId)
                && AuthEndpointPolicy.isSafeUrl(redirectUri, true)
                && AuthEndpointPolicy.isSafeUrl(frontendBaseUrl, false)
                && AuthEndpointPolicy.isSafeUrl(authorizationUri, true)
                && AuthEndpointPolicy.isSafeUrl(tokenUri, true)
                && AuthEndpointPolicy.isSafeUrl(userInfoUri, true)
                && (expectedAppId == null || (expectedAppId > 0 && AuthEndpointPolicy.isSafeUrl(tokenInfoUri, true)));
    }
}
