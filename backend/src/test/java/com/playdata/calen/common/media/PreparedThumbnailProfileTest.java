package com.playdata.calen.common.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PreparedThumbnailProfileTest {

    @Test
    void shouldSnapRequestedWidthsToPreparedThumbnailProfiles() {
        assertThat(PreparedThumbnailProfile.selectWidth(null)).isEqualTo(480);
        assertThat(PreparedThumbnailProfile.selectWidth(50)).isEqualTo(96);
        assertThat(PreparedThumbnailProfile.selectWidth(120)).isEqualTo(240);
        assertThat(PreparedThumbnailProfile.selectWidth(400)).isEqualTo(480);
        assertThat(PreparedThumbnailProfile.selectWidth(900)).isEqualTo(960);
        assertThat(PreparedThumbnailProfile.selectWidth(1400)).isEqualTo(960);
    }
}
