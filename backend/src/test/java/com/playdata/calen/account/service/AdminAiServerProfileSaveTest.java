package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.dto.AdminAiServerProfileSecretUpdateRequest;
import com.playdata.calen.account.security.AdminOpsSecretCipher;
import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AdminAiServerProfileSaveTest {
    @Test void savingAndReloadingPreservesSharedHostsAndTheSameEncryptedProfileKey() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        var properties = new LedgerAiAnalysisProperties();
        var cipher = new AdminOpsSecretCipher("test-key-for-admin-ops");
        var service = create(jdbc, properties, cipher);
        service.initializePersistedSettings();
        String key = UUID.randomUUID().toString();
        var result = service.updateAiServerProfileSecret(request(key, "example-secret", false, "localhost,100.99.1.4", 5L, 300L));
        assertThat(result.credentialConfigured()).isTrue();
        assertThat(result.allowedProviderHosts()).isEqualTo("localhost,100.99.1.4");
        assertThat(properties.getAllowedProviderHosts()).isEqualTo("localhost,100.99.1.4");
        assertThat(properties.getLedger().getApiKey()).isNullOrEmpty();
        assertThat(jdbc.queryForList("select setting_value from admin_ops_control_settings", String.class)).noneMatch(value -> value.contains("example-secret"));
        var reloaded = new LedgerAiAnalysisProperties();
        var restarted = create(jdbc, reloaded, cipher);
        restarted.initializePersistedSettings();
        assertThat(reloaded.getAllowedProviderHosts()).isEqualTo("localhost,100.99.1.4");
        assertThat(reloaded.getConnectTimeout().toSeconds()).isEqualTo(5);
        assertThat(restarted.updateAiServerProfileSecret(request(key, null, false, null, null, null)).credentialConfigured()).isTrue();
        assertThat(restarted.updateAiServerProfileSecret(request(key, null, true, null, null, null)).credentialConfigured()).isFalse();
        assertThat(restarted.updateAiServerProfileSecret(request(UUID.randomUUID().toString(), null, false, null, null, null)).credentialConfigured()).isFalse();
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("example-secret");
    }

    @Test void invalidSharedSettingsDoNotSaveCredentials() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        var service = create(jdbc, new LedgerAiAnalysisProperties(), new AdminOpsSecretCipher("test-key-for-admin-ops"));
        service.initializePersistedSettings();
        assertThatThrownBy(() -> service.updateAiServerProfileSecret(request(UUID.randomUUID().toString(), "secret", false, "a".repeat(4001), 5L, 300L))).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("select count(*) from admin_ops_control_settings", Long.class)).isZero();
    }

    @Test void rollbackDoesNotApplySharedSettingsOrPersistCredentials() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        var properties = new LedgerAiAnalysisProperties();
        var service = create(jdbc, properties, new AdminOpsSecretCipher("test-key-for-admin-ops"));
        service.initializePersistedSettings();
        var originalHosts = properties.getAllowedProviderHosts();
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            service.updateAiServerProfileSecret(request(UUID.randomUUID().toString(), "secret", false, "new.example.test", 5L, 300L));
            assertThat(properties.getAllowedProviderHosts()).isEqualTo(originalHosts);
            status.setRollbackOnly();
        });
        assertThat(properties.getAllowedProviderHosts()).isEqualTo(originalHosts);
        assertThat(jdbc.queryForObject("select count(*) from admin_ops_control_settings", Long.class)).isZero();
        transaction.executeWithoutResult(status -> service.updateAiServerProfileSecret(request(UUID.randomUUID().toString(), "secret", false, "new.example.test", 5L, 300L)));
        assertThat(properties.getAllowedProviderHosts()).isEqualTo("new.example.test");
    }

    private AdminOpsControlService create(JdbcTemplate jdbc, LedgerAiAnalysisProperties properties, AdminOpsSecretCipher cipher) {
        return new AdminOpsControlService(properties, new MinioProperties(), mock(MinioBackupArchiveService.class), jdbc, new ObjectMapper(), cipher);
    }

    private AdminAiServerProfileSecretUpdateRequest request(String key, String secret, boolean clear, String hosts, Long connect, Long read) {
        return new AdminAiServerProfileSecretUpdateRequest(key, "openai", null, false, secret, clear, null, false, true, hosts, connect, read, null);
    }
}
