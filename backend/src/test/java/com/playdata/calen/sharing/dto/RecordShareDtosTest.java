package com.playdata.calen.sharing.dto;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.sharing.domain.RecordShareKind;
import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordShareDtosTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void legacyPayloadWithoutShareMemoStillDeserializes() throws Exception {
        var request = mapper.readValue("""
                {"groupId":5,"kind":"LEDGER","sourceId":100,"recipientIds":[2]}
                """, RecordShareDtos.Create.class);
        assertThat(request.shareMemo()).isNull();
        assertThat(request.recipientIds()).containsExactly(2L);
    }

    @Test void validatesOptionalMemoAndRejectsOverTheLimit() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String memo : new String[] {null, "", "가".repeat(500)}) {
                var request = new RecordShareDtos.Create(5L, RecordShareKind.LEDGER, 100L, List.of(2L), memo);
                assertThat(validator.validate(request)).isEmpty();
                var decoded = mapper.readValue(mapper.writeValueAsString(request), RecordShareDtos.Create.class);
                assertThat(decoded.shareMemo()).isEqualTo(memo);
            }
            var invalid = new RecordShareDtos.Create(5L, RecordShareKind.LEDGER, 100L, List.of(2L), "가".repeat(501));
            assertThat(validator.validate(invalid)).singleElement().satisfies(violation -> {
                assertThat(violation.getPropertyPath().toString()).isEqualTo("shareMemo");
                assertThat(violation.getMessage()).isEqualTo("공유 메모는 500자까지 입력할 수 있습니다.");
            });
        }
    }
}
