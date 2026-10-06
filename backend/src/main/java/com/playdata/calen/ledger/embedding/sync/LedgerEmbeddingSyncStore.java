package com.playdata.calen.ledger.embedding.sync;

import com.playdata.calen.common.embedding.EmbeddingDocument;
import com.playdata.calen.ledger.embedding.LedgerEmbeddingDocumentService;
import com.playdata.calen.ledger.embedding.LedgerEmbeddingRecordType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Only bounded DB transactions live here; HTTP calls must never be made inside these methods. */
@Component
public class LedgerEmbeddingSyncStore {
    private static final String KEY = "owner_id = ? and record_type = ? and source_id = ?";
    private static final String CLAIM = KEY + " and state = 'PROCESSING' and claim_token = ? and claimed_revision = ? and lease_until > current_timestamp(6)";
    private final JdbcTemplate jdbc;
    private final LedgerEmbeddingSyncProperties properties;
    private final LedgerEmbeddingDocumentService documents;
    private final TransactionTemplate transactions;

    public LedgerEmbeddingSyncStore(JdbcTemplate jdbc, LedgerEmbeddingSyncProperties properties,
            LedgerEmbeddingDocumentService documents, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.documents = documents;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(30);
    }

    public void recoverExpired() {
        transactions.executeWithoutResult(status -> jdbc.update("""
                update ledger_embedding_sync_jobs set
                    attempts = case when revision <> claimed_revision then 0 else attempts end,
                    state = case when revision <> claimed_revision or attempts < ? then 'PENDING' else 'FAILED' end,
                    next_attempt_at = timestampadd(microsecond, ?, current_timestamp(6)),
                    last_error_code = 'LEASE_EXPIRED', claim_token = null, claimed_revision = null,
                    lease_until = null, updated_at = current_timestamp(6)
                where state = 'PROCESSING' and lease_until <= current_timestamp(6)
                """, properties.getMaxAttempts(), micros(properties.getRecoveryDelay())));
    }

    public List<Claim> claimBatch(int limit) {
        return transactions.execute(status -> {
            // Conditional updates also protect against independent workers without SKIP LOCKED support.
            List<Candidate> candidates = jdbc.query("""
                    select owner_id, record_type, source_id, revision, state
                    from ledger_embedding_sync_jobs
                    where state in ('PENDING', 'SUCCEEDED') and next_attempt_at <= current_timestamp(6)
                    order by next_attempt_at, owner_id, record_type, source_id limit ?
                    """, (row, index) -> new Candidate(row.getLong("owner_id"), type(row.getString("record_type")),
                            row.getLong("source_id"), row.getLong("revision"), row.getString("state")), limit);
            List<Claim> claims = new ArrayList<>();
            for (Candidate candidate : candidates) {
                String token = UUID.randomUUID().toString();
                int updated = jdbc.update("""
                        update ledger_embedding_sync_jobs set
                            attempts = case when state = 'SUCCEEDED' then 1 else attempts + 1 end,
                            state = 'PROCESSING', claim_token = ?, claimed_revision = revision,
                            lease_until = timestampadd(microsecond, ?, current_timestamp(6)), updated_at = current_timestamp(6)
                        where """ + " " + KEY + " and revision = ? and state = ? and next_attempt_at <= current_timestamp(6)",
                        token, micros(properties.getLeaseDuration()), candidate.ownerId(), candidate.type().metadataValue(),
                        candidate.sourceId(), candidate.revision(), candidate.state());
                if (updated == 1) {
                    int attempts = jdbc.queryForObject("select attempts from ledger_embedding_sync_jobs where " + KEY,
                            Integer.class, candidate.ownerId(), candidate.type().metadataValue(), candidate.sourceId());
                    claims.add(new Claim(candidate.ownerId(), candidate.type(), candidate.sourceId(), candidate.revision(), token, attempts));
                }
            }
            return List.copyOf(claims);
        });
    }

    /** Null means a lost/superseded claim; a nonnull prepared item with null document means DELETE. */
    public Prepared prepareLatest(Claim claim) {
        return transactions.execute(status -> {
            List<Long> revisions = jdbc.query("select revision from ledger_embedding_sync_jobs where " + CLAIM + " for update",
                    (row, index) -> row.getLong(1), claimArgs(claim));
            if (revisions.isEmpty()) return null;
            if (revisions.get(0) != claim.revision()) {
                releaseSupersededInTransaction(claim);
                return null;
            }
            // Lock only the outbox row. Source writes may wait to enqueue; reads use MVCC, not source locks.
            return new Prepared(claim, documents.findCurrentForSync(claim.ownerId(), claim.type(), claim.sourceId()).orElse(null));
        });
    }

    public boolean isCurrent(Claim claim) {
        return transactions.execute(status -> jdbc.queryForObject("select count(*) from ledger_embedding_sync_jobs where "
                + CLAIM + " and revision = ?", Integer.class, append(claimArgs(claim), claim.revision())) == 1);
    }

    public void releaseSuperseded(Claim claim) {
        transactions.executeWithoutResult(status -> releaseSupersededInTransaction(claim));
    }

    private void releaseSupersededInTransaction(Claim claim) {
        jdbc.update("""
                update ledger_embedding_sync_jobs set state = 'PENDING', attempts = 0,
                    next_attempt_at = current_timestamp(6), claim_token = null, claimed_revision = null,
                    lease_until = null, last_error_code = null, updated_at = current_timestamp(6)
                where """ + " " + CLAIM + " and revision <> ?", append(claimArgs(claim), claim.revision()));
    }

    public void succeeded(Claim claim) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                update ledger_embedding_sync_jobs set
                    applied_revision = greatest(applied_revision, ?),
                    state = case when revision = ? then 'SUCCEEDED' else 'PENDING' end,
                    attempts = 0,
                    next_attempt_at = case when revision = ? then timestampadd(microsecond, ?, current_timestamp(6))
                        else current_timestamp(6) end,
                    claim_token = null, claimed_revision = null, lease_until = null,
                    last_error_code = null, updated_at = current_timestamp(6)
                where """ + " " + CLAIM, prepend(claimArgs(claim), claim.revision(), claim.revision(), claim.revision(),
                        micros(properties.getReconcileInterval()))));
    }

    public void failed(Claim claim, boolean retryable, String code) {
        // Callers pass only fixed enum-like codes or HTTP_<number>, never exception text.
        if (!code.matches("[A-Z0-9_]{1,50}")) throw new IllegalArgumentException("unsafe embedding failure code");
        boolean retry = retryable && claim.attempts() < properties.getMaxAttempts();
        transactions.executeWithoutResult(status -> jdbc.update("""
                update ledger_embedding_sync_jobs set
                    attempts = case when revision = ? then attempts else 0 end,
                    state = case when revision <> ? or ? then 'PENDING' else 'FAILED' end,
                    next_attempt_at = case when revision <> ? then current_timestamp(6)
                        else timestampadd(microsecond, ?, current_timestamp(6)) end,
                    last_error_code = case when revision = ? then ? else null end,
                    claim_token = null, claimed_revision = null, lease_until = null, updated_at = current_timestamp(6)
                where """ + " " + CLAIM, prepend(claimArgs(claim), claim.revision(), claim.revision(), retry,
                        claim.revision(), micros(retryDelay(claim.attempts())), claim.revision(), code)));
    }

    private Duration retryDelay(int attempts) {
        long base = properties.getRetryBaseDelay().toMillis();
        long maximum = properties.getRetryMaxDelay().toMillis();
        // Saturation avoids overflow for a configured attempt limit greater than 63.
        for (int index = 1; index < attempts && base < maximum; index++) base = base > maximum / 2 ? maximum : base * 2;
        return Duration.ofMillis(Math.min(base, maximum));
    }

    private static Object[] claimArgs(Claim claim) {
        return new Object[] {claim.ownerId(), claim.type().metadataValue(), claim.sourceId(), claim.token(), claim.revision()};
    }
    private static Object[] prepend(Object[] tail, Object... head) {
        Object[] result = java.util.Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, result, head.length, tail.length);
        return result;
    }
    private static Object[] append(Object[] head, Object value) {
        Object[] result = java.util.Arrays.copyOf(head, head.length + 1);
        result[head.length] = value;
        return result;
    }
    private static long micros(Duration duration) { return Math.multiplyExact(duration.toMillis(), 1_000L); }
    private static LedgerEmbeddingRecordType type(String value) {
        for (LedgerEmbeddingRecordType type : LedgerEmbeddingRecordType.values()) if (type.metadataValue().equals(value)) return type;
        throw new IllegalArgumentException("unsupported embedding record type");
    }

    private record Candidate(long ownerId, LedgerEmbeddingRecordType type, long sourceId, long revision, String state) { }
    public record Claim(long ownerId, LedgerEmbeddingRecordType type, long sourceId, long revision, String token, int attempts) { }
    public record Prepared(Claim claim, EmbeddingDocument document) { }
}
