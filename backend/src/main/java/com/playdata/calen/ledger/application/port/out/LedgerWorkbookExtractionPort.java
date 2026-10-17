package com.playdata.calen.ledger.application.port.out;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.Extraction;

/** Provider-independent result candidates. No entity, SDK, HTTP envelope or Jackson type crosses this boundary. */
public interface LedgerWorkbookExtractionPort { Extraction extract(String workbookPayloadJson); }
