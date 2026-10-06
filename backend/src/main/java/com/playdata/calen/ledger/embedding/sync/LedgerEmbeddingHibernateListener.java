package com.playdata.calen.ledger.embedding.sync;

import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.RecurringLedgerRule;
import com.playdata.calen.ledger.embedding.LedgerEmbeddingRecordType;
import java.util.Set;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;

/** Flush-time events cover dirty checking, direct repository imports and delegated service writes. */
final class LedgerEmbeddingHibernateListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {
    private final LedgerEmbeddingOutboxCapture capture;

    LedgerEmbeddingHibernateListener(LedgerEmbeddingOutboxCapture capture) { this.capture = capture; }

    @Override public void onPostInsert(PostInsertEvent event) { record(event.getEntity(), event.getSession()); }
    @Override public void onPostDelete(PostDeleteEvent event) { record(event.getEntity(), event.getSession()); }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        if (!capture.isEnabled()) return;
        record(event.getEntity(), event.getSession());
        // Display order/visibility are not document fields; renames and payment kind are.
        String column = null;
        if (event.getEntity() instanceof CategoryGroup && changed(event, Set.of("name"))) column = "category_group_id";
        if (event.getEntity() instanceof CategoryDetail && changed(event, Set.of("name"))) column = "category_detail_id";
        if (event.getEntity() instanceof PaymentMethod && changed(event, Set.of("name", "kind"))) column = "payment_method_id";
        if (column != null) {
            String referenceColumn = column;
            event.getSession().doWork(connection -> capture.referencedRecords(connection, referenceColumn, ((Number) event.getId()).longValue()));
        }
    }

    private void record(Object entity, EventSource session) {
        if (!capture.isEnabled()) return;
        if (entity instanceof LedgerEntry entry) {
            session.doWork(connection -> capture.record(connection, entry.getOwner().getId(), LedgerEmbeddingRecordType.LEDGER_ENTRY, entry.getId()));
        } else if (entity instanceof RecurringLedgerRule rule) {
            session.doWork(connection -> capture.record(connection, rule.getOwner().getId(), LedgerEmbeddingRecordType.RECURRING_RULE, rule.getId()));
        }
    }

    private boolean changed(PostUpdateEvent event, Set<String> names) {
        int[] dirty = event.getDirtyProperties();
        if (dirty == null) return true;
        String[] properties = event.getPersister().getPropertyNames();
        for (int index : dirty) if (names.contains(properties[index])) return true;
        return false;
    }

    @Override public boolean requiresPostCommitHandling(EntityPersister persister) { return false; }
}
