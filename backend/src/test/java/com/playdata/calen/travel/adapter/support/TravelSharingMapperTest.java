package com.playdata.calen.travel.adapter.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.playdata.calen.travel.domain.model.TravelSharingData.*;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelSharingMapperTest {
    final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test void groupAndRecipientJsonKeepExistingNamesNestedFieldsOrderAndNulls() throws Exception {
        var time = LocalDateTime.of(2026, 10, 16, 12, 34);
        var group = new Group(3L, "Family", List.of(new Recipient(2L, "recipient", "Recipient"), new Recipient(4L, "other", null)), time, time);
        assertThat(json.readTree(json.writeValueAsBytes(TravelSharingMapper.group(group)))).isEqualTo(json.readTree("""
                {"id":3,"name":"Family","recipients":[{"userId":2,"loginId":"recipient","displayName":"Recipient"},
                {"userId":4,"loginId":"other","displayName":null}],"createdAt":"2026-10-16T12:34:00","updatedAt":"2026-10-16T12:34:00"}
                """));
    }

    @Test void shareJsonStillCallsItsTimeSharedAtNotCreatedAt() throws Exception {
        var share = new Share(9L, 3L, "Trip", "recipient", "Recipient", LocalDateTime.of(2026, 10, 16, 12, 34));
        assertThat(json.readTree(json.writeValueAsBytes(TravelSharingMapper.share(share)))).isEqualTo(json.readTree("""
                {"id":9,"planId":3,"planName":"Trip","recipientLoginId":"recipient","recipientDisplayName":"Recipient","sharedAt":"2026-10-16T12:34:00"}
                """));
    }

    @Test void disabledPublicShareKeepsBoxedFalseAndNullTime() throws Exception {
        assertThat(json.readTree(json.writeValueAsBytes(TravelSharingMapper.publicShare(new PublicShare(3L, false, null)))))
                .isEqualTo(json.readTree("{\"planId\":3,\"publicShared\":false,\"publicSharedAt\":null}"));
    }
}
