package com.playdata.calen.ledger.application.port.out;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.Workbook;

public interface PreparedLedgerWorkbookPort { Workbook read(String preparedInput); }
