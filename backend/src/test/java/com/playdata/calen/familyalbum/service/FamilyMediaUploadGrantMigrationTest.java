package com.playdata.calen.familyalbum.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class FamilyMediaUploadGrantMigrationTest {
    @Test void migrationIsRepeatableAndDoesNotAddUrlStorage() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:family-grant-migration-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261103_045__family_media_upload_grants.sql"));
        migration.execute(ds);
        jdbc.update("insert into family_media_upload_grants(owner_id,category_id,object_key,storage_bucket,signed_at_utc,expires_at_utc) values (1,2,'family-media/1/2/file','bucket',current_timestamp,current_timestamp)");
        migration.execute(ds);
        assertThat(jdbc.queryForObject("select count(*) from family_media_upload_grants", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from INFORMATION_SCHEMA.COLUMNS where TABLE_NAME='FAMILY_MEDIA_UPLOAD_GRANTS' and COLUMN_NAME like '%URL%'", Integer.class)).isZero();
    }
}
