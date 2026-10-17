package com.playdata.calen.ledger.adapter.support;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.SourceReference;
import java.util.Map;
import java.util.Set;

/** Existing persisted input JSON layout; names/order must remain restart-compatible. */
public final class PreparedLedgerWorkbookFormat {
    private PreparedLedgerWorkbookFormat() {}
    public record KnownLedgerChoices(Set<String> paymentMethods, Map<String,Set<String>> categoryDetailsByGroup,
            ArrayNode paymentMethodsPayload, ArrayNode categoriesPayload) {}
    public record WorkbookPayload(String fileName, String sheetName, int includedRows, int skippedRows, KnownLedgerChoices knownChoices,
            Map<String,SourceReference> sourceReferencesById, Map<String,SourceReference> sourceReferencesByLocation, ObjectNode payload) {}
}
