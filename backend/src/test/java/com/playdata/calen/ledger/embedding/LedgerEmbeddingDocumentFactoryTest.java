package com.playdata.calen.ledger.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.domain.RecurringLedgerMode;
import com.playdata.calen.ledger.domain.RecurringLedgerRule;
import com.playdata.calen.ledger.domain.RecurringLedgerScheduleType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class LedgerEmbeddingDocumentFactoryTest {

    private final LedgerEmbeddingDocumentFactory factory = new LedgerEmbeddingDocumentFactory();

    @Test void blankMemoIsIncludedAndTextHashMatchesIndependentPythonFixture() {
        var document = factory.fromEntry(entry());
        assertThat(document.text()).isEqualTo("기록 유형: 가계부 거래\n입출금 구분: 지출\n제목: synthetic-title\n대분류: 식비\n소분류: 외식\n메모: ");
        assertThat(document.metadata()).containsEntry("domain", "ledger").containsEntry("record_type", "ledger_entry")
                .containsEntry("owner_id", 7L).containsEntry("source_id", 123L).containsEntry("text_format_version", 1)
                .containsEntry("text_hash", "30641cda6b2c10837b25dec26be2c3d7fbdc1feeafa3e6f342c69fc5b622ea4f");
        assertThat(document.id().toString()).isEqualTo("478b3f6d-a9a4-5cbc-8399-bd378f740e80");
    }

    @Test void nullAndEmptyMemoCreateTheSameIncludedDocumentText() {
        LedgerEntry entry = entry();
        entry.setMemo(null);
        var nullMemo = factory.fromEntry(entry);
        entry.setMemo("");
        var emptyMemo = factory.fromEntry(entry);
        assertThat(nullMemo.text()).endsWith("\n메모: ").isEqualTo(emptyMemo.text());
        assertThat(nullMemo.metadata().get("text_hash")).isEqualTo(emptyMemo.metadata().get("text_hash"));
    }

    @Test void preservesWhitespaceAndOriginalMemoButNormalizesLineEndingsOnly() {
        LedgerEntry entry = entry();
        entry.setTitle("  original title  ");
        entry.setMemo("  first\r\nsecond\rthird\n  ");
        var crlf = factory.fromEntry(entry);
        entry.setMemo("  first\nsecond\nthird\n  ");
        var lf = factory.fromEntry(entry);
        assertThat(crlf.text()).contains("제목:   original title  ").endsWith("메모:   first\nsecond\nthird\n  ");
        assertThat(crlf.text()).isEqualTo(lf.text());
        assertThat(crlf.metadata().get("text_hash")).isEqualTo(lf.metadata().get("text_hash"));
    }

    @Test void structuredFieldsStayInMetadataWithoutInventingTravelText() {
        LedgerEntry entry = entry();
        entry.setTravelPlanId(90L);
        entry.setTravelRecordId(91L);
        entry.setForeignCurrencyCode("USD");
        entry.setForeignAmount(new BigDecimal("9.1234"));
        entry.setExchangeRateToKrw(new BigDecimal("1300.123456"));
        var document = factory.fromEntry(entry);
        assertThat(document.text()).doesNotContain("12345.60", "2026-10-05", "Synthetic Payment", "여행", "방문", "USD");
        assertThat(document.metadata()).containsEntry("amount", "12345.60").containsEntry("currency", "KRW")
                .containsEntry("entry_date", "2026-10-05").containsEntry("entry_time", "14:30")
                .containsEntry("entry_date_epoch_day", LocalDate.of(2026, 10, 5).toEpochDay())
                .containsEntry("payment_method_id", 12L).containsEntry("payment_method_name", "Synthetic Payment")
                .containsEntry("foreign_amount", "9.1234").containsEntry("exchange_rate_to_krw", "1300.123456")
                .containsEntry("travel_plan_id", 90L).containsEntry("travel_record_id", 91L);
    }

    @Test void incomeAndMissingOptionalFieldsUseOriginalEmptyLabels() {
        LedgerEntry entry = entry();
        entry.setEntryType(EntryType.INCOME);
        entry.setCategoryDetail(null);
        entry.setEntryTime(null);
        var document = factory.fromEntry(entry);
        assertThat(document.text()).contains("입출금 구분: 수입", "\n소분류: \n메모: ");
        assertThat(document.metadata()).containsEntry("entry_type", "INCOME")
                .doesNotContainKeys("category_detail_id", "category_detail_name", "entry_time", "active");
    }

    @Test void inactiveRecurringRuleIsIndependentAndKeepsScheduleInMetadata() {
        LedgerEntry entry = entry();
        RecurringLedgerRule rule = new RecurringLedgerRule();
        rule.setId(123L);
        rule.setOwner(entry.getOwner());
        rule.setTitle("synthetic recurring income");
        rule.setEntryType(EntryType.INCOME);
        rule.setAmount(new BigDecimal("2500000.00"));
        rule.setCategoryGroup(entry.getCategoryGroup());
        rule.setMode(RecurringLedgerMode.CONFIRM);
        rule.setScheduleType(RecurringLedgerScheduleType.EVERY_N_DAYS);
        rule.setIntervalDays(23);
        rule.setStartDate(LocalDate.of(2026, 10, 5));
        rule.setActive(false);
        var document = factory.fromRule(rule);
        assertThat(document.text()).startsWith("기록 유형: 정기 입출금 규칙\n입출금 구분: 수입").endsWith("\n메모: ")
                .doesNotContain("2500000", "2026-10-05", "23");
        assertThat(document.metadata()).containsEntry("record_type", "recurring_rule").containsEntry("active", false)
                .containsEntry("schedule_type", "EVERY_N_DAYS").containsEntry("interval_days", 23)
                .containsEntry("start_date", "2026-10-05").containsEntry("mode", "CONFIRM")
                .doesNotContainKeys("payment_method_id", "end_date", "entry_date");
        assertThat(document.id().toString()).isEqualTo("136b73b8-3e73-5317-bcb3-76e8d75cac5b");
        rule.setActive(true);
        rule.setAmount(new BigDecimal("2600000.00"));
        assertThat(factory.fromRule(rule).id()).isEqualTo(document.id());
        assertThat(factory.fromRule(rule).metadata().get("text_hash")).isEqualTo(document.metadata().get("text_hash"));
    }

    @Test void metadataOnlyChangesKeepHashButTextChangesKeepIdAndChangeHash() {
        LedgerEntry entry = entry();
        var before = factory.fromEntry(entry);
        entry.setAmount(new BigDecimal("10.00"));
        entry.setEntryDate(LocalDate.of(2026, 10, 6));
        var metadataChanged = factory.fromEntry(entry);
        assertThat(metadataChanged.id()).isEqualTo(before.id());
        assertThat(metadataChanged.metadata().get("text_hash")).isEqualTo(before.metadata().get("text_hash"));
        assertThat(metadataChanged.metadata().get("amount")).isNotEqualTo(before.metadata().get("amount"));
        entry.setMemo("new original memo");
        var textChanged = factory.fromEntry(entry);
        assertThat(textChanged.id()).isEqualTo(before.id());
        assertThat(textChanged.metadata().get("text_hash")).isNotEqualTo(before.metadata().get("text_hash"));
    }

    @Test void deletedAndUnsavedEntriesAreRejected() {
        LedgerEntry entry = entry();
        entry.setDeletedAt(LocalDateTime.of(2026, 10, 5, 1, 0));
        assertThatThrownBy(() -> factory.fromEntry(entry)).isInstanceOf(IllegalArgumentException.class);
        entry.setDeletedAt(null);
        entry.setId(null);
        assertThatThrownBy(() -> factory.fromEntry(entry)).isInstanceOf(IllegalArgumentException.class);
        entry.setId(123L);
        entry.getOwner().setId(null);
        assertThatThrownBy(() -> factory.fromEntry(entry)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void metadataIsDetachedImmutableAndDocumentLogsRedactSourceContent() {
        LedgerEntry entry = entry();
        entry.setMemo("synthetic-private-memo");
        var document = factory.fromEntry(entry);
        entry.setTitle("changed after construction");
        assertThat(document.metadata()).containsEntry("title", "synthetic-title");
        assertThatThrownBy(() -> document.metadata().put("owner_id", 99L)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(document.toString()).doesNotContain("synthetic-private-memo", "synthetic-title");
    }

    private LedgerEntry entry() {
        AppUser owner = new AppUser();
        owner.setId(7L);
        CategoryGroup group = new CategoryGroup();
        group.setId(10L);
        group.setOwner(owner);
        group.setName("식비");
        CategoryDetail detail = new CategoryDetail();
        detail.setId(11L);
        detail.setGroup(group);
        detail.setName("외식");
        PaymentMethod payment = new PaymentMethod();
        payment.setId(12L);
        payment.setName("Synthetic Payment");
        payment.setKind(PaymentMethodKind.CARD);
        LedgerEntry entry = new LedgerEntry();
        entry.setId(123L);
        entry.setOwner(owner);
        entry.setTitle("synthetic-title");
        entry.setMemo("");
        entry.setEntryType(EntryType.EXPENSE);
        entry.setCategoryGroup(group);
        entry.setCategoryDetail(detail);
        entry.setPaymentMethod(payment);
        entry.setAmount(new BigDecimal("12345.60"));
        entry.setEntryDate(LocalDate.of(2026, 10, 5));
        entry.setEntryTime(LocalTime.of(14, 30));
        return entry;
    }
}
