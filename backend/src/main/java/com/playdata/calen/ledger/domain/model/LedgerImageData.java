package com.playdata.calen.ledger.domain.model;

import com.playdata.calen.ledger.domain.EntryType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** Provider-independent image evidence, before user review or ledger persistence. */
public final class LedgerImageData {
    private LedgerImageData() {}

    /** Lazy bytes preserve file-read timing and avoid copying an image into the workflow model. */
    public interface ImageContent {
        String contentType();
        byte[] bytes();
    }

    public static final class ImageReadFailure extends RuntimeException {
        public ImageReadFailure(Throwable cause) { super(cause); }
    }

    public record ImageExtraction(
            boolean ok,
            String error,
            String documentType,
            String rawText,
            ParsedImageEntry parsed,
            List<ParsedImageEntry> parsedEntries,
            Map<String, Object> timing
    ) {
    }

    public record ParsedImageEntry(
            LocalDate entryDate,
            LocalTime entryTime,
            EntryType entryType,
            String title,
            String memo,
            BigDecimal amount,
            String currencyCode,
            BigDecimal foreignAmount,
            String vendor,
            String paymentMethodText,
            String categoryGroupName,
            String categoryDetailName,
            String categoryText,
            List<ImageLineItem> lineItems,
            Double confidence,
            List<String> warnings
    ) {
        public ParsedImageEntry(
                LocalDate entryDate,
                LocalTime entryTime,
                EntryType entryType,
                String title,
                String memo,
                BigDecimal amount,
                String vendor,
                String paymentMethodText,
                String categoryGroupName,
                String categoryDetailName,
                String categoryText,
                List<ImageLineItem> lineItems,
                Double confidence,
                List<String> warnings
        ) {
            this(entryDate, entryTime, entryType, title, memo, amount, null, null,
                    vendor, paymentMethodText, categoryGroupName, categoryDetailName,
                    categoryText, lineItems, confidence, warnings);
        }
    }

    public record ImageLineItem(
            String itemName,
            BigDecimal quantity,
            String unit,
            BigDecimal price
    ) {
    }
}
