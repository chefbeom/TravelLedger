package com.playdata.calen.familyalbum.dto;

import java.util.List;

public record FamilyAlbumBootstrapResponse(
        Long currentUserId,
        List<FamilyUserOptionResponse> users,
        List<FamilyCategoryResponse> categories,
        long totalPhotoCount,
        long totalVideoCount,
        List<FamilyAlbumResponse> albums
) {
}
