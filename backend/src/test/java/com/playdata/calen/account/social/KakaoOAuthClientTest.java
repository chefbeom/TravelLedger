package com.playdata.calen.account.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.playdata.calen.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoOAuthClientTest {

    private static final String TOKEN_URI = "http://kauth.test/oauth/token";
    private static final String USER_INFO_URI = "http://kapi.test/v2/user/me";

    @Test
    void buildsAuthorizationUrlWithConfiguredRedirectAndState() {
        KakaoOAuthProperties properties = configuredProperties();
        KakaoOAuthClient client = new KakaoOAuthClient(RestClient.builder().build(), properties);

        String url = client.authorizationUrl("safe-state");

        assertThat(url).contains("client_id=client-id");
        assertThat(url).contains("redirect_uri=https://app.test/api/auth/oauth/kakao/callback");
        assertThat(url).contains("state=safe-state");
        assertThat(url).contains("scope=account_email%20profile_nickname");
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

    private KakaoOAuthProperties configuredProperties() {
        KakaoOAuthProperties properties = new KakaoOAuthProperties();
        properties.setEnabled(true);
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        properties.setRedirectUri("https://app.test/api/auth/oauth/kakao/callback");
        properties.setFrontendBaseUrl("https://app.test");
        properties.setAuthorizationUri("http://kauth.test/oauth/authorize");
        properties.setTokenUri(TOKEN_URI);
        properties.setUserInfoUri(USER_INFO_URI);
        return properties;
    }
}
