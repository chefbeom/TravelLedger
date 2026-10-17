package com.playdata.calen.travel.adapter.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.domain.model.TravelMediaData.*;
import com.playdata.calen.travel.dto.*;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelMediaUploadMapperTest {
    @Test void prepareMapsFileMetadataWithoutTrimmingOrRoundingAndKeepsAbsentLists() {
        var request = new TravelMediaUploadPrepareRequest(TravelMediaType.PHOTO, " caption ", List.of(
                new TravelMediaUploadFileRequest(" photo.jpg ", "image/jpeg", 11,
                        List.of(new TravelPreparedThumbnailUploadRequest("480", "image/png", 5)))));
        assertThat(TravelMediaUploadMapper.candidates(request)).containsExactly(new Candidate(" photo.jpg ", "image/jpeg", 11,
                List.of(new ThumbnailCandidate("480", "image/png", 5))));
        assertThat(TravelMediaUploadMapper.candidates(new TravelMediaUploadPrepareRequest(TravelMediaType.PHOTO, null, null))).isNull();
    }

    @Test void completePreservesRawGpsUntilThePolicyRunsAndDoesNotChangeNullElementValidationTiming() {
        var lat = new BigDecimal("37.123456789");
        var file = new TravelMediaUploadCompleteFileRequest("key", "photo.jpg", "image/jpeg", 11,
                Arrays.asList(new TravelPreparedThumbnailUploadCompleteRequest("480", "thumb", "image/png", 5), null), lat, null);
        var result = TravelMediaUploadMapper.completed(new TravelMediaUploadCompleteRequest(TravelMediaType.PHOTO, null, Arrays.asList(file, null)));
        assertThat(result.get(0).gpsLatitude()).isSameAs(lat);
        assertThat(result.get(0).gpsLongitude()).isNull();
        assertThat(result.get(0).thumbnails()).containsExactly(new CompletedThumbnail("480", "thumb", "image/png", 5), null);
        assertThat(result.get(1)).isNull();
        assertThat(TravelMediaUploadMapper.completed(new TravelMediaUploadCompleteRequest(TravelMediaType.PHOTO, null, null))).isNull();
    }

    @Test void presignJsonRetainsTheExistingNamesAndNestedThumbnailFields() throws Exception {
        var response = TravelMediaUploadMapper.response(new Prepared("PRESIGNED", List.of(new Target("PUT", "https://storage.invalid/file",
                "key", "stored.jpg", "photo.jpg", "image/jpeg", 11,
                List.of(new ThumbnailTarget("480", "PUT", "https://storage.invalid/thumb", "thumb", "image/png", 5))))));
        var json = new ObjectMapper();
        var actual = json.readTree(json.writeValueAsBytes(response));
        assertThat(actual).isEqualTo(json.readTree("""
                {"uploadMode":"PRESIGNED","uploads":[{"method":"PUT","uploadUrl":"https://storage.invalid/file",
                "objectKey":"key","storedFileName":"stored.jpg","originalFileName":"photo.jpg","contentType":"image/jpeg","fileSize":11,
                "thumbnails":[{"variant":"480","method":"PUT","uploadUrl":"https://storage.invalid/thumb","objectKey":"thumb","contentType":"image/png","fileSize":5}]}]}
                """));
    }
}
