package com.playdata.calen.ledger.embedding.sync;

/** MariaDB DML only. Schema ownership remains with Flyway, not startup runners or Hibernate DDL. */
final class LedgerEmbeddingOutboxSql {
    private LedgerEmbeddingOutboxSql() { }

    static final String INSERT = """
            insert into ledger_embedding_sync_jobs
                (owner_id, record_type, source_id, revision, applied_revision, state, attempts,
                 next_attempt_at, created_at, updated_at)
            """;
    static final String MERGE = """
            on duplicate key update
                revision = revision + 1,
                attempts = case when state = 'PROCESSING' then attempts else 0 end,
                next_attempt_at = case when state = 'PROCESSING' then next_attempt_at else current_timestamp(6) end,
                last_error_code = case when state = 'PROCESSING' then last_error_code else null end,
                state = case when state = 'PROCESSING' then state else 'PENDING' end,
                updated_at = current_timestamp(6)
            """;
    static final String ENQUEUE = INSERT + """
            values (?, ?, ?, 1, 0, 'PENDING', 0, current_timestamp(6), current_timestamp(6), current_timestamp(6))
            """ + MERGE;

    static String enqueueSelection(String table, String type, String predicate) {
        // All arguments are source-code constants, never request values.
        return INSERT + "select owner_id, '" + type + "', id, 1, 0, 'PENDING', 0, "
                + "current_timestamp(6), current_timestamp(6), current_timestamp(6) from " + table
                + " where " + predicate + " order by owner_id, id " + MERGE;
    }
}
