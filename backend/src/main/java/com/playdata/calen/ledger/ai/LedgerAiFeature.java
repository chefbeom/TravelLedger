package com.playdata.calen.ledger.ai;

import java.util.Locale;

public enum LedgerAiFeature {
    LEDGER_ANALYSIS("ledger"),
    IMAGE_ANALYSIS("image"),
    EXCEL_IMPORT("excel");

    private final String settingKey;

    LedgerAiFeature(String settingKey) {
        this.settingKey = settingKey;
    }

    public String settingKey() {
        return settingKey;
    }

    public static LedgerAiFeature from(String value) {
        if (value == null || value.isBlank()) {
            return LEDGER_ANALYSIS;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "image", "image-analysis", "ocr", "receipt" -> IMAGE_ANALYSIS;
            case "excel", "excel-import", "spreadsheet" -> EXCEL_IMPORT;
            default -> LEDGER_ANALYSIS;
        };
    }
}