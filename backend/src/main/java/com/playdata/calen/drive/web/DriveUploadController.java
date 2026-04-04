package com.playdata.calen.drive.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.service.DriveService;
import com.playdata.calen.drive.service.DriveStorageService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/file/upload")
@RequiredArgsConstructor
public class DriveUploadController {

    private final DriveStorageService driveStorageService;
    private final DriveService driveService;

    @PostMapping
    public List<DriveDtos.UploadChunkResponse> initializeUpload(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody List<DriveDtos.UploadInitRequest> requests
    ) {
        return driveStorageService.initUpload(currentUser.userId(), requests);
    }

    @PostMapping("/complete")
    public DriveDtos.UploadCompleteResponse completeUpload(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody DriveDtos.UploadCompleteRequest request
    ) {
        return driveService.completeUpload(currentUser.userId(), request);
    }

    @PostMapping("/abort")
    public DriveDtos.ActionResponse abortUpload(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody DriveDtos.UploadAbortRequest request
    ) {
        return driveStorageService.abortUpload(request);
    }
}
