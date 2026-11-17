package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class TravelUploadGrantMigrationTest {
    @Test void migrationIsRepeatableAndDoesNotStoreUrls() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:travel-grant-migration-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261103_046__travel_upload_grants.sql"));
        migration.execute(ds);
        jdbc.update("insert into travel_upload_grants(owner_id,plan_id,source_id,kind,object_key,storage_bucket,signed_at_utc,expires_at_utc) values (1,2,3,'MEDIA','travel-media/1/2/3/file','bucket',current_timestamp,current_timestamp)");
        migration.execute(ds);
        assertThat(jdbc.queryForObject("select count(*) from travel_upload_grants", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from INFORMATION_SCHEMA.COLUMNS where TABLE_NAME='TRAVEL_UPLOAD_GRANTS' and COLUMN_NAME like '%URL%'", Integer.class)).isZero();
    }
}
