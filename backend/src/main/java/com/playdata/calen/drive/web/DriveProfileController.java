package com.playdata.calen.drive.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.service.DriveProfileService;
import com.playdata.calen.drive.service.DriveService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/feater/settings")
@RequiredArgsConstructor
public class DriveProfileController {

    private final DriveProfileService driveProfileService;

    @GetMapping("/me")
    public DriveDtos.ProfileSettingsResponse getSettings(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return driveProfileService.getSettings(currentUser.userId());
    }

    @PutMapping("/me")
    public DriveDtos.ProfileSettingsResponse updateSettings(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestBody DriveDtos.ProfileSettingsUpdateRequest request
    ) {
        return driveProfileService.updateSettings(currentUser.userId(), request);
    }

    @PostMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DriveDtos.ProfileSettingsResponse uploadProfileImage(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam("image") MultipartFile image
    ) {
        return driveProfileService.uploadProfileImage(currentUser.userId(), image);
    }

    @GetMapping("/me/profile-image")
    public ResponseEntity<byte[]> getProfileImage(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        DriveService.DriveFilePayload payload = driveProfileService.loadProfileImage(currentUser.userId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentLength(payload.contentLength())
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .body(payload.bytes());
    }
}
