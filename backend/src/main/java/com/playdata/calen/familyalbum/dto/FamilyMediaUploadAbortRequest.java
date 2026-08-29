package com.playdata.calen.familyalbum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record FamilyMediaUploadAbortRequest(
        @NotEmpty List<@NotBlank String> objectKeys
) {
}
