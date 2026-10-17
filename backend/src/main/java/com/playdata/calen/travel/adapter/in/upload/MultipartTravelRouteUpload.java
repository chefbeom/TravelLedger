package com.playdata.calen.travel.adapter.in.upload;

import com.playdata.calen.travel.application.port.out.TravelRouteUploadPort.Upload;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

/** Metadata and bytes are delegated lazily; construction never reads the uploaded content. */
public record MultipartTravelRouteUpload(MultipartFile file) implements Upload {
    @Override public String name(){return file.getName();}
    @Override public String fileName(){return file.getOriginalFilename();}
    @Override public String contentType(){return file.getContentType();}
    @Override public long size(){return file.getSize();}
    @Override public boolean empty(){return file==null||file.isEmpty();}
    @Override public byte[] bytes() throws IOException {return file.getBytes();}
}
