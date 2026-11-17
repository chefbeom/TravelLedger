package com.playdata.calen.account.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

class KakaoOAuthClientTest {

    private static final String TOKEN_URI = "https://kauth.test/oauth/token";
    private static final String USER_INFO_URI = "https://kapi.test/v2/user/me";
    private static final String TOKEN_INFO_URI = "https://kapi.test/v1/user/access_token_info";
    private static final String UNLINK_URI = "https://kapi.test/v1/user/unlink";

    @Test
    void buildsAuthorizationUrlWithConfiguredRedirectAndState() {
        KakaoOAuthProperties properties = configuredProperties();
        KakaoOAuthClient client = new KakaoOAuthClient(RestClient.builder().build(), properties);

        String url = client.authorizationUrl("safe-state");

        assertThat(url).contains("client_id=client-id");
        assertThat(url).contains("redirect_uri=https://app.test/api/auth/oauth/kakao/callback");
        assertThat(url).contains("state=safe-state");
        String scope = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("scope");
        assertThat(UriUtils.decode(scope, StandardCharsets.UTF_8)).isEqualTo("account_email,profile_nickname");
    }

    @Test
    void exchangesCodeAndMapsOnlyVerifiedKakaoIdentity() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KakaoOAuthClient client = new KakaoOAuthClient(builder.build(), configuredProperties());

        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"test-access-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_INFO_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-access-token"))
                .andRespond(withSuccess(
                        "{\"id\":12345,\"properties\":{\"nickname\":\"카카오 사용자\"},"
                                + "\"kakao_account\":{\"email\":\"User@Example.com\","
                                + "\"is_email_valid\":true,\"is_email_verified\":true}}",
                        MediaType.APPLICATION_JSON
                ));

        KakaoIdentity identity = client.fetchIdentity("authorization-code");

        assertThat(identity.providerUserId()).isEqualTo("12345");
        assertThat(identity.email()).isEqualTo("user@example.com");
        assertThat(identity.displayName()).isEqualTo("카카오 사용자");
        assertThat(identity.emailVerified()).isTrue();
        server.verify();
    }

    @Test
    void recordsAppOriginOnlyWhenTokenInfoAndProfileAgree() {
        KakaoIdentity identity = readProfileWithAppInfo("{\"id\":12345,\"app_id\":1234,\"expires_in\":3600}");
        assertThat(identity.providerUserId()).isEqualTo("12345");
        assertThat(identity.providerAppId()).isEqualTo(1234L);
    }

    @Test
    void refusesWrongAppDifferentSubjectMissingOrExpiredTokenInfo() {
        for (String info : List.of(
                "{\"id\":12345,\"app_id\":999,\"expires_in\":3600}",
                "{\"id\":999,\"app_id\":1234,\"expires_in\":3600}",
                "{\"id\":12345,\"expires_in\":3600}",
                "{\"id\":\"12345\",\"app_id\":1234,\"expires_in\":3600}",
                "{\"id\":12345,\"app_id\":1234,\"expires_in\":0}")) {
            assertThatThrownBy(() -> readProfileWithAppInfo(info))
                    .isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining("test-access-token");
        }
    }

    @Test
    void tokenInfoProviderFailureNeverAuthenticatesOrLeaksAccessToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess("{\"access_token\":\"test-access-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_INFO_URI)).andRespond(withSuccess(verifiedProfile(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN_INFO_URI)).andRespond(withServerError());
        KakaoOAuthProperties properties = configuredProperties();
        properties.setExpectedAppId(1234L); properties.setTokenInfoUri(TOKEN_INFO_URI);
        assertThatThrownBy(() -> new KakaoOAuthClient(builder.build(), properties).fetchIdentity("authorization-code"))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining("test-access-token");
        server.verify();
    }

    @Test
    void rejectsKakaoProfileWithoutVerifiedEmail() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KakaoOAuthClient client = new KakaoOAuthClient(builder.build(), configuredProperties());

        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"test-access-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_INFO_URI))
                .andRespond(withSuccess(
                        "{\"id\":12345,\"kakao_account\":{\"email\":\"user@example.com\","
                                + "\"is_email_valid\":true,\"is_email_verified\":false}}",
                        MediaType.APPLICATION_JSON
                ));

        assertThatThrownBy(() -> client.fetchIdentity("authorization-code"))
                .isInstanceOf(BadRequestException.class);
        server.verify();
    }

    @Test
    void readsNicknameFromTheConsentedAccountProfile() {
        KakaoIdentity identity = readProfile("{\"id\":12345,\"kakao_account\":{\"email\":\"user@example.com\","
                + "\"is_email_valid\":true,\"is_email_verified\":true,\"profile\":{\"nickname\":\"프로필 닉네임\"}}}");
        assertThat(identity.displayName()).isEqualTo("프로필 닉네임");
    }

    @Test
    void missingProviderIdentityCannotBecomeALinkedAccount() {
        assertThatThrownBy(() -> readProfile("{\"id\":\"\"}"))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> readProfile("{\"kakao_account\":{}}"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void unlinkUsesServerAdminKeyAndChecksTheReturnedUserId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KakaoOAuthProperties properties = configuredProperties();
        properties.setAdminKey("test-admin-key");
        properties.setUnlinkUri(UNLINK_URI);
        KakaoOAuthClient client = new KakaoOAuthClient(builder.build(), properties);
        server.expect(requestTo(UNLINK_URI)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "KakaoAK test-admin-key"))
                .andExpect(content().string(allOf(containsString("target_id_type=user_id"), containsString("target_id=12345"))))
                .andRespond(withSuccess("{\"id\":12345}", MediaType.APPLICATION_JSON));
        client.unlinkAccount("12345");
        server.verify();
    }

    @Test
    void unlinkFailsClosedForMissingKeyMismatchedResultAndProviderError() {
        KakaoOAuthProperties properties = configuredProperties();
        assertThatThrownBy(() -> new KakaoOAuthClient(RestClient.builder().build(), properties).unlinkAccount("12345"))
                .isInstanceOf(ServiceUnavailableException.class);
        properties.setAdminKey("test-admin-key");
        properties.setUnlinkUri(UNLINK_URI);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(UNLINK_URI)).andRespond(withSuccess("{\"id\":999}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(UNLINK_URI)).andRespond(withServerError());
        KakaoOAuthClient client = new KakaoOAuthClient(builder.build(), properties);
        assertThatThrownBy(() -> client.unlinkAccount("12345")).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> client.unlinkAccount("12345")).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> client.unlinkAccount("not-a-user-id")).isInstanceOf(BadRequestException.class);
        server.verify();
    }

    @Test
    void refusesRemoteCleartextOrAmbiguousAuthenticationEndpoints() {
        List<Consumer<KakaoOAuthProperties>> unsafeEndpoints = List.of(
                properties -> properties.setRedirectUri("http://app.example.test/callback"),
                properties -> properties.setFrontendBaseUrl("http://192.168.35.123:8081"),
                properties -> properties.setAuthorizationUri("http://kauth.example.test/oauth/authorize"),
                properties -> properties.setTokenUri("http://kauth.example.test/oauth/token"),
                properties -> properties.setUserInfoUri("http://kapi.example.test/v2/user/me"),
                properties -> properties.setTokenUri("https://user:password@kauth.example.test/oauth/token"),
                properties -> properties.setFrontendBaseUrl("https://app.example.test/?next=other"));
        for (Consumer<KakaoOAuthProperties> configure : unsafeEndpoints) {
            KakaoOAuthProperties properties = configuredProperties();
            configure.accept(properties);
            KakaoOAuthClient client = new KakaoOAuthClient(RestClient.builder().build(), properties);
            assertThat(client.isConfigured()).isFalse();
            assertThatThrownBy(() -> client.fetchIdentity("sensitive-code"))
                    .isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining("sensitive-code");
        }
        KakaoOAuthProperties unlink = configuredProperties();
        unlink.setAdminKey("sensitive-admin-key"); unlink.setUnlinkUri("http://kapi.example.test/v1/user/unlink");
        KakaoOAuthClient client = new KakaoOAuthClient(RestClient.builder().build(), unlink);
        assertThat(client.isUnlinkConfigured()).isFalse();
        assertThatThrownBy(() -> client.unlinkAccount("12345"))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageNotContaining("sensitive-admin-key");
        KakaoOAuthProperties info = configuredProperties();
        info.setExpectedAppId(1234L); info.setTokenInfoUri("http://kapi.example.test/access_token_info");
        assertThat(info.isConfigured()).isFalse();
        info.setTokenInfoUri(TOKEN_INFO_URI); info.setExpectedAppId(0L);
        assertThat(info.isConfigured()).isFalse();
    }

    @Test
    void permitsLoopbackHttpForLocalDevelopment() {
        KakaoOAuthProperties properties = configuredProperties();
        properties.setRedirectUri("http://127.0.0.1:8080/api/auth/oauth/kakao/callback");
        properties.setFrontendBaseUrl("http://localhost:5173");
        properties.setAuthorizationUri("http://[::1]:8088/authorize");
        properties.setTokenUri("http://localhost:8088/token");
        properties.setUserInfoUri("http://localhost:8088/user");
        properties.setAdminKey("test-key"); properties.setUnlinkUri("http://localhost:8088/unlink");
        KakaoOAuthClient client = new KakaoOAuthClient(RestClient.builder().build(), properties);
        assertThat(client.isConfigured()).isTrue();
        assertThat(client.isUnlinkConfigured()).isTrue();
    }

    @Test
    void preservesAnExistingHttpsAuthorizationEndpointQuery() {
        KakaoOAuthProperties properties = configuredProperties();
        properties.setAuthorizationUri("https://kauth.test/oauth/authorize?custom=value");
        KakaoOAuthClient client = new KakaoOAuthClient(RestClient.builder().build(), properties);
        assertThat(client.isConfigured()).isTrue();
        assertThat(client.authorizationUrl("safe-state")).contains("custom=value").contains("state=safe-state");
    }

    private KakaoIdentity readProfile(String profile) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess("{\"access_token\":\"test-access-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_INFO_URI)).andRespond(withSuccess(profile, MediaType.APPLICATION_JSON));
        return new KakaoOAuthClient(builder.build(), configuredProperties()).fetchIdentity("authorization-code");
    }

    private KakaoIdentity readProfileWithAppInfo(String tokenInfo) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess("{\"access_token\":\"test-access-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USER_INFO_URI)).andRespond(withSuccess(verifiedProfile(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN_INFO_URI)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-access-token"))
                .andRespond(withSuccess(tokenInfo, MediaType.APPLICATION_JSON));
        KakaoOAuthProperties properties = configuredProperties();
        properties.setExpectedAppId(1234L); properties.setTokenInfoUri(TOKEN_INFO_URI);
        try {
            return new KakaoOAuthClient(builder.build(), properties).fetchIdentity("authorization-code");
        } finally {
            server.verify();
        }
    }

    private String verifiedProfile() {
        return "{\"id\":12345,\"kakao_account\":{\"email\":\"user@example.test\","
                + "\"is_email_valid\":true,\"is_email_verified\":true,\"profile\":{\"nickname\":\"사용자\"}}}";
    }

    private KakaoOAuthProperties configuredProperties() {
        KakaoOAuthProperties properties = new KakaoOAuthProperties();
        properties.setEnabled(true);
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        properties.setRedirectUri("https://app.test/api/auth/oauth/kakao/callback");
        properties.setFrontendBaseUrl("https://app.test");
        properties.setAuthorizationUri("https://kauth.test/oauth/authorize");
        properties.setTokenUri(TOKEN_URI);
        properties.setUserInfoUri(USER_INFO_URI);
        return properties;
    }
}
