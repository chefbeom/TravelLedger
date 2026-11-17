package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class UnknownLoginAuditPrivacyMigrationTest {
    @Test void migrationPreservesExistingRowsAndCatchesLateLegacyNodeInserts() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:login-audit-migration-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table login_audit_logs (id bigint primary key, app_user_id bigint, status varchar(40), login_id varchar(60))");
        jdbc.update("insert into login_audit_logs(id,status,login_id) values (1,'BAD_CREDENTIALS','old')");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261103_042__unknown_login_audit_anonymization.sql"));
        migration.execute(ds); migration.execute(ds);
        assertThat(jdbc.queryForObject("select unknown_failure_privacy_processed from login_audit_logs where id=1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select login_id from login_audit_logs where id=1", String.class)).isEqualTo("old");
        jdbc.update("insert into login_audit_logs(id,status,login_id) values (2,'BLOCKED','late')");
        assertThat(jdbc.queryForObject("select unknown_failure_privacy_processed from login_audit_logs where id=2", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from INFORMATION_SCHEMA.INDEXES where upper(INDEX_NAME) = 'IDX_LOGIN_UNKNOWN_PRIVACY'", Integer.class)).isEqualTo(1);
    }
}
