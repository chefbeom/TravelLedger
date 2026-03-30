package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.SupportInquiryResponse;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.SupportInquiryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/support/inquiries")
@RequiredArgsConstructor
public class SupportInquiryController {

    private final SupportInquiryService supportInquiryService;

    @GetMapping("/me")
    public List<SupportInquiryResponse> getMyInquiries(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return supportInquiryService.getMyInquiries(currentUser.userId());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SupportInquiryResponse> createInquiry(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam("title") String title,
            @RequestParam("content") String content,
            @RequestParam(value = "attachment", required = false) MultipartFile attachment
    ) {
        return ResponseEntity.ok(supportInquiryService.createInquiry(currentUser.userId(), title, content, attachment));
    }

    @GetMapping("/{inquiryId}/attachment")
    public ResponseEntity<Resource> getAttachment(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long inquiryId
    ) {
        SupportInquiryService.AttachmentPayload payload = supportInquiryService.loadAttachment(
                currentUser.userId(),
                currentUser.isAdmin(),
                inquiryId
        );

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (payload.contentType() != null && !payload.contentType().isBlank()) {
            mediaType = MediaType.parseMediaType(payload.contentType());
        }

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(payload.fileName()).build().toString()
                )
                .contentType(mediaType)
                .body(payload.resource());
    }
}
