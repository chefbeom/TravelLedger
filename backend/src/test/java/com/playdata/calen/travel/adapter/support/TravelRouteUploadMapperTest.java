package com.playdata.calen.travel.adapter.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.travel.domain.model.TravelRouteUploadData.*;
import com.playdata.calen.travel.dto.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class TravelRouteUploadMapperTest {
    @Test void multipartBridgeIsLazyRetainsNullSlotsAndDelegatesOnlyOriginalGetBytes() throws Exception {
        var file=mock(MultipartFile.class);var uploads=TravelRouteUploadMapper.uploads(Arrays.asList(null,file));
        assertThat(uploads.get(0)).isNull();verifyNoInteractions(file);
        when(file.getOriginalFilename()).thenReturn("route.gpx");when(file.getContentType()).thenReturn("xml");when(file.getSize()).thenReturn(3L);
        var bytes=new byte[]{1,2,3};when(file.getBytes()).thenReturn(bytes);
        assertThat(uploads.get(1).bytes()).isSameAs(bytes);assertThat(uploads.get(1).fileName()).isEqualTo("route.gpx");
        assertThat(uploads.get(1).size()).isEqualTo(3);verify(file,never()).getInputStream();
        var failure=new IOException("synthetic");when(file.getBytes()).thenThrow(failure);
        assertThat(catchThrowable(()->uploads.get(1).bytes())).isSameAs(failure);assertThat(TravelRouteUploadMapper.uploads(null)).isNull();
    }
    @Test void requestCandidatesAndCompletionProofsAreUnmodifiedNotPrevalidated() {
        assertThat(TravelRouteUploadMapper.candidates(new TravelRouteGpxUploadPrepareRequest(Arrays.asList(null,new TravelRouteGpxUploadFileRequest(" route.gpx "," xml ",3)))))
                .containsExactly(null,new Candidate(" route.gpx "," xml ",3));
        assertThat(TravelRouteUploadMapper.completed(new TravelRouteGpxUploadCompleteRequest(List.of(new TravelRouteGpxUploadCompleteFileRequest("test/key","route.gpx","xml",3)))))
                .containsExactly(new Completed("test/key","route.gpx","xml",3));
    }
    @Test void responseJsonIsByteCompatibleWhileInternalValueToStringsRedactPresignedData() throws Exception {
        var target=new Target("PUT","https://storage.invalid/synthetic?signature=example","test/key","stored","route.gpx","xml",3);
        var response=TravelRouteUploadMapper.response(new Prepared("PRESIGNED",List.of(target)));
        var expected=new TravelRouteGpxUploadPrepareResponse("PRESIGNED",List.of(new TravelRouteGpxUploadTargetResponse(target.method(),target.uploadUrl(),
                target.objectKey(),target.storedFileName(),target.originalFileName(),target.contentType(),target.fileSize())));
        var json=new ObjectMapper();assertThat(json.writeValueAsBytes(response)).isEqualTo(json.writeValueAsBytes(expected));
        assertThat(target.toString()).doesNotContain(target.uploadUrl(),target.objectKey());
        assertThat(new Completed("test/private","route.gpx","xml",3).toString()).doesNotContain("test/private");
    }
}
