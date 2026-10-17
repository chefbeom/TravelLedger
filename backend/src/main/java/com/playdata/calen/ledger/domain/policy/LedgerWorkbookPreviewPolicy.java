package com.playdata.calen.ledger.domain.policy;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

public final class LedgerWorkbookPreviewPolicy {
    private static final int MAX_PREVIEW_ROWS = 500;
    private static final Pattern DIGITS = Pattern.compile("[^0-9.\\-]");
    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy-M-d"), DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"), DateTimeFormatter.ofPattern("yyyyMMdd"));
    private LedgerWorkbookPreviewPolicy() {}

    public static Preview preview(Workbook workbook, Extraction extraction) {
        List<PreviewRow> rows = new ArrayList<>(); Set<String> accepted = new HashSet<>();
        int unlinked = 0, duplicate = 0;
        for (Candidate candidate : extraction.rows()) {
            if (rows.size() >= MAX_PREVIEW_ROWS) break;
            SourceReference source = source(workbook, candidate);
            if (source == null) { unlinked++; continue; }
            if (!accepted.add(source.sourceId())) { duplicate++; continue; }
            rows.add(row(rows.size()+1, candidate, source, workbook.choices()));
        }
        if (rows.isEmpty()) throw new BadRequestException("AI가 원본 Excel 행과 연결된 거래를 찾지 못했습니다. AI 응답의 sourceId와 JSON 형식을 확인해 주세요.");
        List<String> notes = new ArrayList<>();
        notes.add("AI가 원본 Excel 행과 연결된 결과만 미리보기에 표시했습니다. 선택한 항목만 최종 저장됩니다.");
        if (unlinked > 0) notes.add("원본 행을 확인할 수 없는 AI 결과 " + unlinked + "건은 제외했습니다.");
        if (duplicate > 0) notes.add("같은 원본 행을 중복 참조한 AI 결과 " + duplicate + "건은 제외했습니다.");
        notes.addAll(extraction.notes());
        int ready = (int) rows.stream().filter(PreviewRow::ready).count();
        return new Preview(workbook.fileName(), workbook.sheetName(), rows.size(), ready, rows.size()-ready, notes, rows);
    }

    private static SourceReference source(Workbook workbook, Candidate row) {
        String id = text(row.sourceId());
        // An explicit but unknown sourceId must never fall back to a forged sheet/row location.
        if (!id.isEmpty()) return workbook.sourcesById().get(id);
        String sheet = text(row.sourceSheetName());
        if (sheet.isEmpty() || row.sourceRowNumber() < 1) return null;
        return workbook.sourcesByLocation().get(locationKey(sheet,row.sourceRowNumber()));
    }

    private static PreviewRow row(int index, Candidate row, SourceReference source, Choices choices) {
        List<String> issues = new ArrayList<>(); LocalDate date = date(row.entryDate());
        if (date == null) issues.add("거래일 확인 필요");
        LocalTime time = time(row.entryTime()); String title = text(row.title());
        if (title.isEmpty()) issues.add("내용 확인 필요");
        BigDecimal amount = amount(row.amount());
        if (amount == null || amount.signum() <= 0) issues.add("금액 확인 필요");
        EntryType type = type(row.entryType(),issues);
        String payment = text(row.paymentMethodName());
        if (!payment.isEmpty() && !choices.paymentMethods().contains(payment)) { issues.add("등록된 결제수단 확인 필요"); payment = ""; }
        String group = text(row.categoryGroupName()), detail = text(row.categoryDetailName());
        if (!group.isEmpty()) {
            Set<String> allowed = choices.categoryDetailsByGroup().get(group);
            if (allowed == null) { issues.add("등록된 대분류 확인 필요"); group = ""; detail = ""; }
            else if (!detail.isEmpty() && !allowed.contains(detail)) { issues.add("등록된 분류 확인 필요"); detail = ""; }
        } else if (!detail.isEmpty()) { issues.add("대분류 없는 분류 확인 필요"); detail = ""; }
        issues.addAll(row.issues());
        return new PreviewRow(index,source.sheetName(),source.rowNumber(),date,time,title.isEmpty() ? "확인 필요" : title,
                text(row.memo()),amount,type,payment,group,detail,issues.isEmpty(),issues);
    }

    private static LocalDate date(String value) {
        String text = text(value); if (text.isEmpty()) return null;
        for (var format : DATES) { try { return LocalDate.parse(text,format); } catch (DateTimeParseException ignored) {} }
        return null;
    }
    private static LocalTime time(String value) {
        String text = text(value); if (text.length() < 5) return null;
        try { return LocalTime.parse(text.substring(0,5)); } catch (DateTimeParseException ignored) { return null; }
    }
    private static BigDecimal amount(Amount amount) {
        if (amount == null) return null;
        if (amount.number() != null) return amount.number().setScale(2,RoundingMode.HALF_UP).stripTrailingZeros();
        String value = DIGITS.matcher(text(amount.text())).replaceAll("");
        if (value.isBlank() || "-".equals(value) || ".".equals(value)) return null;
        try { return new BigDecimal(value).abs().setScale(2,RoundingMode.HALF_UP).stripTrailingZeros(); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static EntryType type(String value, List<String> issues) {
        String normalized = text(value).toUpperCase(Locale.ROOT);
        if ("INCOME".equals(normalized) || normalized.contains("수입") || normalized.contains("입금")) return EntryType.INCOME;
        if ("EXPENSE".equals(normalized) || normalized.contains("지출") || normalized.contains("출금")) return EntryType.EXPENSE;
        issues.add("수입/지출 구분 확인 필요"); return EntryType.EXPENSE;
    }
    public static String locationKey(String sheet, int row) { return text(sheet) + "\u0000" + row; }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}
