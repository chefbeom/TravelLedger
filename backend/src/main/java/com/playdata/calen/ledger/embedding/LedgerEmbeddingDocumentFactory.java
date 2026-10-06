package com.playdata.calen.ledger.embedding;

import com.playdata.calen.common.embedding.EmbeddingDocument;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.RecurringLedgerRule;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Use the owner-scoped document service, not browser DTOs, to obtain source entities. */
@Component
public class LedgerEmbeddingDocumentFactory {

    public static final int TEXT_FORMAT_VERSION = 1;
    public static final String DOMAIN = "ledger";

    public EmbeddingDocument fromEntry(LedgerEntry entry) {
        Objects.requireNonNull(entry, "persisted ledger entry is required");
        if (entry.getDeletedAt() != null) throw new IllegalArgumentException("deleted ledger entries must not be embedded");
        Long ownerId = Objects.requireNonNull(entry.getOwner(), "source owner is required").getId();
        LedgerEmbeddingRecordType type = LedgerEmbeddingRecordType.LEDGER_ENTRY;
        String text = text(type, entry.getEntryType(), entry.getTitle(), entry.getCategoryGroup(),
                entry.getCategoryDetail(), entry.getMemo());
        Map<String, Object> metadata = commonMetadata(ownerId, type, entry.getId(), text, entry.getEntryType(),
                entry.getTitle(), entry.getAmount(), entry.getCategoryGroup(), entry.getCategoryDetail(), entry.getPaymentMethod());
        put(metadata, "entry_date", entry.getEntryDate());
        put(metadata, "entry_date_epoch_day", entry.getEntryDate() == null ? null : entry.getEntryDate().toEpochDay());
        put(metadata, "entry_time", entry.getEntryTime());
        put(metadata, "foreign_currency_code", entry.getForeignCurrencyCode());
        put(metadata, "foreign_amount", decimal(entry.getForeignAmount()));
        put(metadata, "exchange_rate_to_krw", decimal(entry.getExchangeRateToKrw()));
        put(metadata, "exchange_rate_date", entry.getExchangeRateDate());
        put(metadata, "exchange_rate_provider", entry.getExchangeRateProvider());
        put(metadata, "travel_plan_id", entry.getTravelPlanId());
        put(metadata, "travel_record_id", entry.getTravelRecordId());
        return document(ownerId, type, entry.getId(), text, metadata);
    }

    public EmbeddingDocument fromRule(RecurringLedgerRule rule) {
        Objects.requireNonNull(rule, "persisted recurring rule is required");
        Long ownerId = Objects.requireNonNull(rule.getOwner(), "source owner is required").getId();
        LedgerEmbeddingRecordType type = LedgerEmbeddingRecordType.RECURRING_RULE;
        String text = text(type, rule.getEntryType(), rule.getTitle(), rule.getCategoryGroup(),
                rule.getCategoryDetail(), rule.getMemo());
        Map<String, Object> metadata = commonMetadata(ownerId, type, rule.getId(), text, rule.getEntryType(),
                rule.getTitle(), rule.getAmount(), rule.getCategoryGroup(), rule.getCategoryDetail(), rule.getPaymentMethod());
        metadata.put("active", rule.isActive());
        put(metadata, "schedule_type", rule.getScheduleType());
        put(metadata, "month_interval", rule.getMonthInterval());
        put(metadata, "day_of_month", rule.getDayOfMonth());
        put(metadata, "interval_days", rule.getIntervalDays());
        put(metadata, "start_date", rule.getStartDate());
        put(metadata, "end_date", rule.getEndDate());
        put(metadata, "mode", rule.getMode());
        put(metadata, "created_at", rule.getCreatedAt());
        put(metadata, "updated_at", rule.getUpdatedAt());
        return document(ownerId, type, rule.getId(), text, metadata);
    }

    private EmbeddingDocument document(Long ownerId, LedgerEmbeddingRecordType type, Long sourceId,
            String text, Map<String, Object> metadata) {
        return new EmbeddingDocument(LedgerEmbeddingPointIds.forRecord(ownerId, type, sourceId), text, metadata);
    }

    private String text(LedgerEmbeddingRecordType type, EntryType entryType, String title,
            CategoryGroup group, CategoryDetail detail, String memo) {
        Objects.requireNonNull(entryType, "source entry type is required");
        return "기록 유형: " + type.textLabel()
                + "\n입출금 구분: " + (entryType == EntryType.INCOME ? "수입" : "지출")
                + "\n제목: " + original(title)
                + "\n대분류: " + original(group == null ? null : group.getName())
                + "\n소분류: " + original(detail == null ? null : detail.getName())
                + "\n메모: " + original(memo);
    }

    private Map<String, Object> commonMetadata(Long ownerId, LedgerEmbeddingRecordType type, Long sourceId,
            String text, EntryType entryType, String title, BigDecimal amount,
            CategoryGroup group, CategoryDetail detail, PaymentMethod payment) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("domain", DOMAIN);
        metadata.put("record_type", type.metadataValue());
        metadata.put("owner_id", ownerId);
        metadata.put("source_id", sourceId);
        metadata.put("text_format_version", TEXT_FORMAT_VERSION);
        metadata.put("text_hash", textHash(text));
        metadata.put("entry_type", entryType.name());
        put(metadata, "title", title);
        put(metadata, "amount", decimal(amount));
        metadata.put("currency", "KRW");
        if (group != null) {
            put(metadata, "category_group_id", group.getId());
            put(metadata, "category_group_name", group.getName());
        }
        if (detail != null) {
            put(metadata, "category_detail_id", detail.getId());
            put(metadata, "category_detail_name", detail.getName());
        }
        if (payment != null) {
            put(metadata, "payment_method_id", payment.getId());
            put(metadata, "payment_method_name", payment.getName());
            put(metadata, "payment_method_kind", payment.getKind());
        }
        return metadata;
    }

    private String original(String value) {
        // Preserve original content and whitespace, apart from platform-independent line endings.
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String textHash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("text hashing requires SHA-256 support", exception);
        }
    }

    private String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }

    private void put(Map<String, Object> metadata, String key, Object value) {
        if (value != null) metadata.put(key, value instanceof Number || value instanceof Boolean ? value : value.toString());
    }
}
