package com.playdata.calen.familyalbum.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record FamilyMediaUploadCompleteRequest(
        String caption,
        @NotEmpty List<@Valid FamilyMediaUploadCompleteFileRequest> files
) {
}
