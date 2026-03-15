package com.playdata.calen.familyalbum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record FamilyAlbumCreateRequest(
        @NotNull(message = "앨범 카테고리를 선택하세요.")
        Long categoryId,

        @NotBlank(message = "앨범 이름을 입력하세요.")
        @Size(max = 120, message = "앨범 이름은 120자 이하로 입력하세요.")
        String title,

        @Size(max = 500, message = "설명은 500자 이하로 입력하세요.")
        String description,

        List<Long> mediaIds
) {
}
