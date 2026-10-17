package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.adapter.in.upload.MultipartTravelRouteUpload;
import com.playdata.calen.travel.application.port.out.TravelRouteUploadPort.Upload;
import com.playdata.calen.travel.domain.model.TravelRouteUploadData.*;
import com.playdata.calen.travel.dto.*;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public final class TravelRouteUploadMapper {
    private TravelRouteUploadMapper() {}
    public static List<Upload> uploads(List<MultipartFile> files) {
        return files==null?null:files.stream().map(file->file==null?null:(Upload)new MultipartTravelRouteUpload(file)).toList();
    }
    public static List<Candidate> candidates(TravelRouteGpxUploadPrepareRequest request) {
        return request.files()==null?null:request.files().stream().map(file->file==null?null:new Candidate(file.originalFileName(),file.contentType(),file.fileSize())).toList();
    }
    public static List<Completed> completed(TravelRouteGpxUploadCompleteRequest request) {
        return request.files()==null?null:request.files().stream().map(file->file==null?null:new Completed(file.objectKey(),file.originalFileName(),file.contentType(),file.fileSize())).toList();
    }
    public static TravelRouteGpxUploadPrepareResponse response(Prepared prepared) {
        return new TravelRouteGpxUploadPrepareResponse(prepared.uploadMode(),prepared.uploads().stream().map(file->new TravelRouteGpxUploadTargetResponse(
                file.method(),file.uploadUrl(),file.objectKey(),file.storedFileName(),file.originalFileName(),file.contentType(),file.fileSize())).toList());
    }
}
