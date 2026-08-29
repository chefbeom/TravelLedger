package com.playdata.calen.familyalbum.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record FamilyMediaUploadPrepareRequest(
        @NotEmpty List<@Valid FamilyMediaUploadFileRequest> files
) {
}
