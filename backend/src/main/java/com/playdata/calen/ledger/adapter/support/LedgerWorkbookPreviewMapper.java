package com.playdata.calen.ledger.adapter.support;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.Preview;
import com.playdata.calen.ledger.dto.*;

public final class LedgerWorkbookPreviewMapper {
    private LedgerWorkbookPreviewMapper() {}
    public static LedgerExcelPreviewResponse response(Preview preview) {
        return new LedgerExcelPreviewResponse(preview.fileName(),preview.sheetName(),null,preview.detectedRowCount(),preview.readyRowCount(),preview.skippedRowCount(),preview.notes(),
                preview.rows().stream().map(r -> new LedgerExcelPreviewRowResponse(r.previewIndex(),r.sourceSheetName(),r.sourceRowNumber(),r.entryDate(),r.entryTime(),r.title(),r.memo(),
                        r.amount(),r.entryType(),r.paymentMethodName(),r.categoryGroupName(),r.categoryDetailName(),r.ready(),r.issues())).toList());
    }
}
