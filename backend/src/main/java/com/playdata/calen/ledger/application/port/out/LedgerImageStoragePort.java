package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerImageStorageData.*;
import java.io.IOException;
import java.io.InputStream;

public interface LedgerImageStoragePort {
    boolean supportsStorage();
    StoredImage store(Long ownerId, Long analysisId, Upload image);
    StoredImageContent load(String objectKey, String fileName, String contentType);
    void delete(String objectKey);

    /** Streaming boundary only: the output adapter opens/closes the stream, never the use case. */
    interface Upload {
        String fileName();
        String contentType();
        long size();
        boolean empty();
        InputStream openStream() throws IOException;
    }
}
