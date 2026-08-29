package com.playdata.calen.familyalbum.dto;

import java.util.List;

public record FamilyMediaUploadPrepareResponse(
        String uploadMode,
        List<FamilyMediaUploadTargetResponse> uploads
) {
}
