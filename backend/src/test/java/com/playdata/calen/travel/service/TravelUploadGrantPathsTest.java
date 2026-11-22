package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TravelUploadGrantPathsTest {

    private static final String ORIGINAL = "travel-media/1/2/10/photo.jpg";

    @Test
    void acceptsBothVersionedAndLegacyThumbnailPaths() {
        assertThat(TravelUploadGrantPaths.isThumbnail(1L, 2L, 10L, ORIGINAL, "travel-media/1/2/10/.thumbs-v2/480/photo.jpg")).isTrue();
        assertThat(TravelUploadGrantPaths.isThumbnail(1L, 2L, 10L, ORIGINAL, "travel-media/1/2/10/.thumbs/480/photo.jpg")).isTrue();
    }

    @Test
    void rejectsOtherNamesRecordsAndWidths() {
        assertThat(TravelUploadGrantPaths.isThumbnail(1L, 2L, 10L, ORIGINAL, "travel-media/1/2/10/.thumbs-v2/480/other.jpg")).isFalse();
        assertThat(TravelUploadGrantPaths.isThumbnail(1L, 2L, 10L, ORIGINAL, "travel-media/1/2/11/.thumbs-v2/480/photo.jpg")).isFalse();
        assertThat(TravelUploadGrantPaths.isThumbnail(1L, 2L, 10L, ORIGINAL, "travel-media/1/2/10/.thumbs-v2/500/photo.jpg")).isFalse();
        assertThat(TravelUploadGrantPaths.isThumbnail(1L, 2L, 11L, ORIGINAL, "travel-media/1/2/10/.thumbs-v2/480/photo.jpg")).isFalse();
    }
}
