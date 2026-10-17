package com.playdata.calen.ledger.domain.model;

import com.playdata.calen.ledger.domain.EntryType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LedgerWorkbookData {
    private LedgerWorkbookData() {}
    public record SourceReference(String sourceId, String sheetName, int rowNumber) {}
    public record Choices(Set<String> paymentMethods, Map<String, Set<String>> categoryDetailsByGroup) {}
    /** payloadJson is the existing serialized workbook request, not a provider response envelope. */
    public record Workbook(String fileName, String sheetName, Choices choices, Map<String, SourceReference> sourcesById,
            Map<String, SourceReference> sourcesByLocation, String payloadJson) {}
    // Preserve the legacy distinction: negative JSON numbers remain negative; text amounts use abs().
    public record Amount(BigDecimal number, String text) {}
    public record Candidate(String sourceId, String sourceSheetName, int sourceRowNumber, String entryDate, String entryTime,
            String title, String memo, Amount amount, String entryType, String paymentMethodName, String categoryGroupName,
            String categoryDetailName, List<String> issues) {}
    public record Extraction(List<Candidate> rows, List<String> notes) {}
    public record PreviewRow(int previewIndex, String sourceSheetName, Integer sourceRowNumber, LocalDate entryDate,
            LocalTime entryTime, String title, String memo, BigDecimal amount, EntryType entryType, String paymentMethodName,
            String categoryGroupName, String categoryDetailName, boolean ready, List<String> issues) {}
    public record Preview(String fileName, String sheetName, int detectedRowCount, int readyRowCount, int skippedRowCount,
            List<String> notes, List<PreviewRow> rows) {}
}
