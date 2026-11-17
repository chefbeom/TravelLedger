package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class DriveObjectBucketMigrationTest {
    @Test void oldRowsRemainUnknownAndRepeatedMigrationPreservesKnownBuckets() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:drive-bucket-migration-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table drive_items(id bigint primary key)");
        jdbc.execute("create table drive_item_versions(id bigint primary key)");
        jdbc.execute("create table drive_profile_settings(id bigint primary key)");
        jdbc.update("insert into drive_items(id) values (1)");
        jdbc.update("insert into drive_item_versions(id) values (2)");
        jdbc.update("insert into drive_profile_settings(id) values (3)");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261103_043__drive_object_bucket_provenance.sql"));
        migration.execute(ds);
        assertThat(jdbc.queryForObject("select storage_bucket from drive_items where id=1", String.class)).isNull();
        assertThat(jdbc.queryForObject("select storage_bucket from drive_item_versions where id=2", String.class)).isNull();
        assertThat(jdbc.queryForObject("select profile_image_bucket from drive_profile_settings where id=3", String.class)).isNull();
        jdbc.update("update drive_items set storage_bucket='known-bucket' where id=1");
        migration.execute(ds);
        assertThat(jdbc.queryForObject("select storage_bucket from drive_items where id=1", String.class)).isEqualTo("known-bucket");
    }
}
