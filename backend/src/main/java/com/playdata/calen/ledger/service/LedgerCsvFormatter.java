package com.playdata.calen.ledger.service;

import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

final class LedgerCsvFormatter {

    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final List<String> HEADER = List.of(
            "거래일",
            "시간",
            "제목",
            "메모",
            "금액",
            "구분",
            "대분류",
            "소분류",
            "결제수단",
            "결제종류"
    );

    private LedgerCsvFormatter() {
    }

    static byte[] format(List<LedgerEntryResponse> entries) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(UTF8_BOM);
        appendRow(output, HEADER);

        for (LedgerEntryResponse entry : entries) {
            appendRow(output, List.of(
                    stringify(entry.entryDate()),
                    stringify(entry.entryTime()),
                    safe(entry.title()),
                    safe(entry.memo()),
                    stringify(entry.amount()),
                    stringify(entry.entryType()),
                    safe(entry.categoryGroupName()),
                    safe(entry.categoryDetailName()),
                    safe(entry.paymentMethodName()),
                    stringify(entry.paymentMethodKind())
            ));
        }

        return output.toByteArray();
    }

    static String buildFileName(LocalDate from, LocalDate to) {
        return "ledger-" + from + "_to_" + to + ".csv";
    }

    private static void appendRow(ByteArrayOutputStream output, List<String> columns) {
        String row = String.join(",", columns.stream().map(LedgerCsvFormatter::escapeCell).toList()) + "\r\n";
        output.writeBytes(row.getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeCell(String value) {
        String sanitized = sanitizeFormula(value == null ? "" : value)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\"", "\"\"");
        return "\"" + sanitized + "\"";
    }

    private static String sanitizeFormula(String value) {
        if (value.isEmpty()) {
            return value;
        }

        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }

    private static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
