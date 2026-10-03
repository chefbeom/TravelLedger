package com.playdata.calen.sharing.repository;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class RecordShareMemoMigrationTest {
    @Test void additiveMigrationPreservesExistingRowsAndAllowsOptional500CharacterMemo() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:record-share-memo-migration;MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE record_shares (id BIGINT PRIMARY KEY, snapshot_json TEXT)");
                statement.execute("INSERT INTO record_shares (id, snapshot_json) VALUES (1, '{\"memo\":\"original\"}')");
                try (var resource = getClass().getResourceAsStream("/db/migration/V20261003_031__record_share_memo.sql")) {
                    assertThat(resource).isNotNull();
                    statement.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
                }
                try (var oldRow = statement.executeQuery("SELECT snapshot_json, share_memo FROM record_shares WHERE id = 1")) {
                    assertThat(oldRow.next()).isTrue();
                    assertThat(oldRow.getString("snapshot_json")).isEqualTo("{\"memo\":\"original\"}");
                    assertThat(oldRow.getString("share_memo")).isNull();
                }
            }
            String memo = "가".repeat(500);
            try (var insert = connection.prepareStatement("INSERT INTO record_shares (id, share_memo) VALUES (2, ?)")) {
                insert.setString(1, memo);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT share_memo FROM record_shares WHERE id = 2")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo(memo);
            }
        }
    }
}
