package com.playdata.calen.familyalbum.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record FamilyCategoryCreateRequest(
        @NotBlank(message = "카테고리 이름을 입력하세요.")
        @Size(max = 120, message = "카테고리 이름은 120자 이하로 입력하세요.")
        String name,

        @Size(max = 500, message = "설명은 500자 이하로 입력하세요.")
        String description,

        List<Long> memberUserIds
) {
}
