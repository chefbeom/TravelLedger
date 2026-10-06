package com.playdata.calen.ledger.embedding.sync;

import com.playdata.calen.common.embedding.EmbeddingProperties;
import com.playdata.calen.ledger.embedding.LedgerEmbeddingRecordType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class LedgerEmbeddingOutboxCapture {
    private final EmbeddingProperties apiProperties;
    private final LedgerEmbeddingSyncProperties syncProperties;
    private final JdbcTemplate jdbc;

    public boolean isEnabled() { return apiProperties.isEnabled() || syncProperties.isCaptureEnabled(); }

    /** Called by Hibernate using the exact source-write connection, never a separate transaction. */
    void record(Connection connection, Long ownerId, LedgerEmbeddingRecordType type, Long sourceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LedgerEmbeddingOutboxSql.ENQUEUE)) {
            statement.setLong(1, ownerId);
            statement.setString(2, type.metadataValue());
            statement.setLong(3, sourceId);
            statement.executeUpdate();
        }
    }

    void referencedRecords(Connection connection, String column, Long referenceId) throws SQLException {
        // Called only for the fixed category/payment columns in the Hibernate listener.
        for (LedgerEmbeddingRecordType type : LedgerEmbeddingRecordType.values()) {
            String table = type == LedgerEmbeddingRecordType.LEDGER_ENTRY ? "ledger_entries" : "recurring_ledger_rules";
            String sql = LedgerEmbeddingOutboxSql.enqueueSelection(table, type.metadataValue(), column + " = ?");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, referenceId);
                statement.executeUpdate();
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void beforeEmptyTrash(Long ownerId) {
        if (!isEnabled()) return;
        // Native/bulk deletes bypass entity events. Record every tombstone before removing rows.
        jdbc.update(LedgerEmbeddingOutboxSql.enqueueSelection("ledger_entries", "ledger_entry",
                "owner_id = ? and deleted_at is not null"), ownerId);
    }
}
