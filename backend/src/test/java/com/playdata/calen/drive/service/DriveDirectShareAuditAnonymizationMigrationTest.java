package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class DriveDirectShareAuditAnonymizationMigrationTest {
    @Test void migrationKeepsExistingLogsForTheBoundedWorkerAndSupportsRepeatedExecution() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:drive-audit-migration-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20260629_001__drive_download_link_access_logs.sql")).execute(ds);
        jdbc.update("insert into drive_download_link_access_logs(id,status,token_fingerprint,client_address,accessed_at) "
                + "values (1,'shared_success','old-fingerprint','192.0.2.1',current_timestamp)");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261103_041__direct_share_audit_anonymization.sql"));
        migration.execute(ds); migration.execute(ds);
        assertThat(jdbc.queryForObject("select direct_share_anonymized from drive_download_link_access_logs where id=1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select client_address from drive_download_link_access_logs where id=1", String.class)).isEqualTo("192.0.2.1");
        // A rolling-deployment node on the older code omits the new column.
        jdbc.update("insert into drive_download_link_access_logs(id,status,token_fingerprint,accessed_at) "
                + "values (2,'shared_success','late-old-node',current_timestamp)");
        assertThat(jdbc.queryForObject("select direct_share_anonymized from drive_download_link_access_logs where id=2", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from INFORMATION_SCHEMA.INDEXES where upper(INDEX_NAME) = 'IDX_DRIVE_DIRECT_SHARE_ANONYMIZATION'", Integer.class)).isEqualTo(1);
    }
}
