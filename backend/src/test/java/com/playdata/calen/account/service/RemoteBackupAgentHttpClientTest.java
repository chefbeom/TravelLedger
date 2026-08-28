package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.playdata.calen.account.dto.AdminBackupFileResponse;
import com.playdata.calen.common.exception.BadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RemoteBackupAgentHttpClientTest {

    private static final String AGENT_URL = "http://agent.test:9443";
    private static final String AGENT_TOKEN = "test-token";

    @Test
    void createsMariaDbBackupWithBearerTokenAndExistingResponseShape() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteBackupAgentHttpClient client = new RemoteBackupAgentHttpClient(
                builder.build(), AGENT_URL, AGENT_TOKEN
        );

        server.expect(requestTo(AGENT_URL + "/v1/backups/mariadb"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + AGENT_TOKEN))
                .andRespond(withSuccess(
                        """
                        {"fileName":"calen-2026-08-29-060000.sql.gz","sizeBytes":1234,"modifiedAt":"2026-08-29T06:00:00+09:00"}
                        """,
                        MediaType.APPLICATION_JSON
                ));

        AdminBackupFileResponse response = client.createMariaDbBackup();

        assertThat(response.fileName()).isEqualTo("calen-2026-08-29-060000.sql.gz");
        assertThat(response.sizeBytes()).isEqualTo(1234L);
        assertThat(response.modifiedAt()).isEqualTo("2026-08-29T06:00:00+09:00");
        server.verify();
    }

    @Test
    void listsBackupsUsingServiceQueryAndMapsWrapperResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteBackupAgentHttpClient client = new RemoteBackupAgentHttpClient(
                builder.build(), AGENT_URL + "/", AGENT_TOKEN
        );

        server.expect(requestTo(AGENT_URL + "/v1/backups?service=minio"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("service", "minio"))
                .andExpect(header("Authorization", "Bearer " + AGENT_TOKEN))
                .andRespond(withSuccess(
                        """
                        {"backups":[{"fileName":"calen-minio-2026-08-29-063000.zip","sizeBytes":9876,"modifiedAt":"2026-08-29T06:30:00+09:00"}]}
                        """,
                        MediaType.APPLICATION_JSON
                ));

        List<AdminBackupFileResponse> responses = client.listBackups("minio");

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.fileName()).isEqualTo("calen-minio-2026-08-29-063000.zip");
            assertThat(response.sizeBytes()).isEqualTo(9876L);
        });
        server.verify();
    }

    @Test
    void restoresMariaDbWithJsonFileName() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteBackupAgentHttpClient client = new RemoteBackupAgentHttpClient(
                builder.build(), AGENT_URL, AGENT_TOKEN
        );

        server.expect(requestTo(AGENT_URL + "/v1/restores/mariadb"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + AGENT_TOKEN))
                .andExpect(jsonPath("$.fileName").value("calen-2026-08-29-060000.sql.gz"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.restoreMariaDb("calen-2026-08-29-060000.sql.gz");

        server.verify();
    }

    @Test
    void doesNotExposeBearerTokenWhenAgentReturnsError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteBackupAgentHttpClient client = new RemoteBackupAgentHttpClient(
                builder.build(), AGENT_URL, AGENT_TOKEN
        );

        server.expect(requestTo(AGENT_URL + "/v1/backups/minio"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(client::createMinioBackup)
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("429")
                .hasMessageNotContaining(AGENT_TOKEN);
        server.verify();
    }

    @Test
    void rejectsUnsafeRestoreFileNameBeforeMakingRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RemoteBackupAgentHttpClient client = new RemoteBackupAgentHttpClient(
                builder.build(), AGENT_URL, AGENT_TOKEN
        );

        assertThatThrownBy(() -> client.restoreMariaDb("../backup.sql.gz"))
                .isInstanceOf(BadRequestException.class);
        server.verify();
    }

    @Test
    void rejectsMissingRemoteConfigurationWithoutNetworkCall() {
        RemoteBackupAgentHttpClient client = new RemoteBackupAgentHttpClient(
                RestClient.builder().build(), "", ""
        );

        assertThatThrownBy(client::createMariaDbBackup)
                .isInstanceOf(BadRequestException.class)
                .hasMessageNotContaining(AGENT_TOKEN);
    }
}
