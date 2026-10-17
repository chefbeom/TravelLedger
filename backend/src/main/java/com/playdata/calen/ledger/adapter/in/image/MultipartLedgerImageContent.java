package com.playdata.calen.ledger.adapter.in.image;

import com.playdata.calen.ledger.domain.model.LedgerImageData.ImageContent;
import com.playdata.calen.ledger.domain.model.LedgerImageData.ImageReadFailure;
import com.playdata.calen.ledger.application.port.out.LedgerImageStoragePort;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;

/** Request/stored-image bridge; actual bytes are read only when the extraction adapter needs them. */
public record MultipartLedgerImageContent(MultipartFile file) implements ImageContent, LedgerImageStoragePort.Upload {
    @Override public String fileName() { return file.getOriginalFilename(); }
    @Override public long size() { return file.getSize(); }
    @Override public boolean empty() { return file==null || file.isEmpty(); }
    @Override public InputStream openStream() throws IOException { return file.getInputStream(); }
    @Override public String contentType() { return file.getContentType(); }
    @Override public byte[] bytes() {
        try { return file.getBytes(); }
        catch (IOException failure) { throw new ImageReadFailure(failure); }
    }
}
