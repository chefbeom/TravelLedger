package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class SupportAttachmentProvenanceMigrationTest {
    @Test void migrationPreservesLegacyRowsAndIsRepeatable() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:support-provenance-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table support_inquiries(id bigint primary key, attachment_storage_path varchar(255))");
        jdbc.update("insert into support_inquiries(id,attachment_storage_path) values (1,'legacy/file')");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261103_047__support_attachment_provenance.sql"));
        migration.execute(ds); migration.execute(ds);
        assertThat(jdbc.queryForObject("select attachment_storage_path from support_inquiries where id=1", String.class)).isEqualTo("legacy/file");
        assertThat(jdbc.queryForObject("select attachment_storage_id from support_inquiries where id=1", String.class)).isNull();
        assertThat(jdbc.queryForObject("select attachment_sha256 from support_inquiries where id=1", String.class)).isNull();
    }
}
