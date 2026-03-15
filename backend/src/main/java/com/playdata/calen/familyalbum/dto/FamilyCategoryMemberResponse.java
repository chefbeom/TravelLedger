package com.playdata.calen.familyalbum.dto;

public record FamilyCategoryMemberResponse(
        Long userId,
        String loginId,
        String displayName,
        boolean owner
) {
}
