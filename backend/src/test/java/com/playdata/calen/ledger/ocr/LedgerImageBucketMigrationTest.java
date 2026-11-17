package com.playdata.calen.ledger.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import com.playdata.calen.account.withdrawal.LedgerImageWithdrawalCleanup.ImageTask;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class LedgerImageBucketMigrationTest {
    @Test void cleanupTargetDoesNotExposePrivatePathsInItsStringRepresentation() {
        var target = new ImageTask(7L, 11L, "ledger-image-analysis/private-key", "private-bucket", "private-id", "private-version", "private-etag");
        assertThat(target.toString()).isEqualTo("ImageTask[redacted]")
                .doesNotContain("private-key", "private-bucket", "private-id", "private-version", "private-etag");
    }

    @Test void preservesOriginalKeysAndDoesNotGuessExistingBuckets() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:image-bucket-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table ledger_image_analysis_requests (id bigint primary key, image_object_key varchar(600))");
        jdbc.update("insert into ledger_image_analysis_requests(id, image_object_key) values (?, ?)", 1L, "original-key");
        var migration = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V20261104_051__ledger_image_bucket_provenance.sql"));
        migration.execute(dataSource);
        assertThat(jdbc.queryForObject("select image_object_key from ledger_image_analysis_requests where id=1", String.class)).isEqualTo("original-key");
        assertThat(jdbc.queryForObject("select image_storage_bucket from ledger_image_analysis_requests where id=1", String.class)).isNull();
        jdbc.update("update ledger_image_analysis_requests set image_storage_bucket=? where id=1", "original-bucket");
        migration.execute(dataSource);
        assertThat(jdbc.queryForObject("select image_storage_bucket from ledger_image_analysis_requests where id=1", String.class)).isEqualTo("original-bucket");
        var versionMigration = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V20261104_052__ledger_image_storage_version.sql"));
        versionMigration.execute(dataSource);
        assertThat(jdbc.queryForObject("select image_storage_id from ledger_image_analysis_requests where id=1", String.class)).isNull();
        assertThat(jdbc.queryForObject("select image_object_version from ledger_image_analysis_requests where id=1", String.class)).isNull();
        jdbc.update("update ledger_image_analysis_requests set image_storage_id=?, image_object_version=? where id=1", "test_storage_id_1234", "version-1");
        versionMigration.execute(dataSource);
        assertThat(jdbc.queryForObject("select image_storage_id from ledger_image_analysis_requests where id=1", String.class)).isEqualTo("test_storage_id_1234");
        assertThat(jdbc.queryForObject("select image_object_version from ledger_image_analysis_requests where id=1", String.class)).isEqualTo("version-1");
        var etagMigration = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V20261104_053__ledger_image_storage_etag.sql"));
        etagMigration.execute(dataSource);
        assertThat(jdbc.queryForObject("select image_object_etag from ledger_image_analysis_requests where id=1", String.class)).isNull();
        jdbc.update("update ledger_image_analysis_requests set image_object_etag=? where id=1", "opaque-etag");
        etagMigration.execute(dataSource);
        assertThat(jdbc.queryForObject("select image_object_key from ledger_image_analysis_requests where id=1", String.class)).isEqualTo("original-key");
        assertThat(jdbc.queryForObject("select image_object_etag from ledger_image_analysis_requests where id=1", String.class)).isEqualTo("opaque-etag");
    }

    @Test void oldTaskJsonWithoutEtagRemainsReadable() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var legacy = json.readValue("{\"ownerId\":7,\"analysisId\":11,\"objectKey\":\"old\",\"bucket\":\"bucket\",\"storageId\":\"id\",\"versionId\":\"v1\"}", ImageTask.class);
        assertThat(legacy.versionId()).isEqualTo("v1");
        assertThat(legacy.etag()).isNull();
    }

    @Test void maximumAcceptedProvenanceStillFitsTheEncryptedCleanupTask() throws Exception {
        long owner = Long.MAX_VALUE, image = Long.MAX_VALUE;
        String key = "ledger-image-analysis/" + owner + "/" + image + "/01234567-89ab-cdef-0123-456789abcdef.jpeg";
        var task = new ImageTask(owner, image, key, "a".repeat(63), "s".repeat(80), "\"".repeat(512), "e".repeat(128));
        assertThat(task.validForVersionCleanup()).isTrue();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String payload = json.writeValueAsString(task);
        var cipher = new com.playdata.calen.account.withdrawal.WithdrawalPayloadCipher("fixture-only-encryption-key-with-at-least-32-characters");
        UUID requestId = UUID.randomUUID(), taskId = UUID.randomUUID();
        String encrypted = cipher.encrypt(requestId, taskId, "LEDGER_IMAGE", payload);
        assertThat(encrypted).doesNotContain(key, task.etag());
        assertThat(cipher.decrypt(requestId, taskId, "LEDGER_IMAGE", encrypted)).isEqualTo(payload);
    }
}
