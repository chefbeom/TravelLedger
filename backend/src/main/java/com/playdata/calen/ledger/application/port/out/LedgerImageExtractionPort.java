package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerImageData.ImageContent;
import com.playdata.calen.ledger.domain.model.LedgerImageData.ImageExtraction;

/** One provider call for visible image evidence; never stores a transaction automatically. */
public interface LedgerImageExtractionPort {
    ImageExtraction analyze(ImageContent image, String documentType, String effectiveUserPrompt);
}
