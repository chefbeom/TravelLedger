package com.playdata.calen.ledger.embedding;

public enum LedgerEmbeddingRecordType {
    LEDGER_ENTRY("ledger_entry", "가계부 거래"),
    RECURRING_RULE("recurring_rule", "정기 입출금 규칙");

    private final String metadataValue;
    private final String textLabel;

    LedgerEmbeddingRecordType(String metadataValue, String textLabel) {
        this.metadataValue = metadataValue;
        this.textLabel = textLabel;
    }

    public String metadataValue() { return metadataValue; }
    public String textLabel() { return textLabel; }
}
