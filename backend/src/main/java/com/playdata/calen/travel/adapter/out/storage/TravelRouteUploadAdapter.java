package com.playdata.calen.travel.adapter.out.storage;

import com.playdata.calen.travel.application.port.out.TravelRouteUploadPort;
import com.playdata.calen.travel.domain.model.TravelRouteData.GpxFile;
import com.playdata.calen.travel.domain.model.TravelRouteUploadData.*;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.io.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@RequiredArgsConstructor
public class TravelRouteUploadAdapter implements TravelRouteUploadPort {
    private final TravelMediaStorageService storage;
    @Override public boolean supportsPresigned(){return storage.supportsPresignedUpload();}
    @Override public GpxFile store(Long owner,Long plan,Long route,Upload file) {
        return value(storage.storeRouteGpx(owner,plan,route,file==null?null:new MultipartUpload(file)));
    }
    @Override public List<Target> prepare(Long owner,Long plan,Long route,List<Candidate> files) {
        return storage.preparePresignedRouteGpxUploads(owner,plan,route,files.stream().map(file->
                new TravelMediaStorageService.RouteGpxUploadCandidate(file.originalFileName(),file.contentType(),file.fileSize())).toList())
                .stream().map(file->new Target(file.method(),file.uploadUrl(),file.objectKey(),file.storedFileName(),file.originalFileName(),file.contentType(),file.fileSize())).toList();
    }
    @Override public GpxFile complete(Long owner,Long plan,Long route,Completed file) {
        return value(storage.completePresignedRouteGpxUpload(owner,plan,route,new TravelMediaStorageService.CompletedRouteGpxUpload(
                file.objectKey(),file.originalFileName(),file.contentType(),file.fileSize())));
    }
    @Override public void abort(Long owner,Long plan,Long route,List<String> keys){storage.abortPresignedRouteGpxUploads(owner,plan,route,keys);}
    private GpxFile value(TravelMediaStorageService.StoredTravelMedia file){return new GpxFile(file.originalFileName(),file.storagePath(),file.contentType(),file.fileSize());}

    /** Legacy storage validates metadata before calling getBytes, exactly as for MultipartFile inputs. */
    private record MultipartUpload(Upload content) implements MultipartFile {
        @Override public String getName(){return content.name();}
        @Override public String getOriginalFilename(){return content.fileName();}
        @Override public String getContentType(){return content.contentType();}
        @Override public boolean isEmpty(){return content.empty();}
        @Override public long getSize(){return content.size();}
        @Override public byte[] getBytes() throws IOException {return content.bytes();}
        @Override public InputStream getInputStream() throws IOException {return new ByteArrayInputStream(getBytes());}
        @Override public void transferTo(File target){throw new UnsupportedOperationException("GPX storage consumes the original bytes, not transferTo.");}
    }
}
