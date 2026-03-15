package com.playdata.calen.familyalbum.dto;

import java.util.List;

public record FamilyAlbumBootstrapResponse(
        Long currentUserId,
        List<FamilyUserOptionResponse> users,
        List<FamilyCategoryResponse> categories,
        List<FamilyMediaResponse> mediaItems,
        List<FamilyAlbumResponse> albums
) {
}
