package com.playdata.calen.ledger.adapter.out.ai;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LedgerWorkbookResponseCodecTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LedgerWorkbookResponseCodec codec = new LedgerWorkbookResponseCodec(json);
    @ParameterizedTest @ValueSource(strings={"direct","message","choices","choice-text","content-array","response","data-rows"})
    void acceptedProviderEnvelopesBecomeTheSameCandidates(String format) throws Exception {
        String body = "{\"rows\":[{\"sourceId\":\" row-1 \",\"sourceRowNumber\":7,\"title\":\" Coffee \",\"amount\":-4500,\"issues\":[\" review \",\"\",5]}],\"notes\":[\" note \"]}";
        String response = switch (format) {
            case "message" -> json.writeValueAsString(Map.of("message",Map.of("content",body)));
            case "choices" -> json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content","```json\n"+body+"\n```")))));
            case "choice-text" -> json.writeValueAsString(Map.of("choices",List.of(Map.of("text",body))));
            case "content-array" -> json.writeValueAsString(Map.of("content",List.of(Map.of("text",body.substring(0,20)),body.substring(20))));
            case "response" -> json.writeValueAsString(Map.of("response","Result: "+body));
            case "data-rows" -> "{\"data\":{\"rows\":"+json.readTree(body).path("rows")+"},\"notes\":[\"note\"]}";
            default -> body;
        };
        var result = codec.decode(response); assertThat(result.rows()).hasSize(1); assertThat(result.notes()).containsExactly("note");
        var row = result.rows().get(0); assertThat(row.sourceId()).isEqualTo("row-1"); assertThat(row.title()).isEqualTo("Coffee");
        assertThat(row.amount().number()).isEqualByComparingTo("-4500"); assertThat(row.sourceRowNumber()).isEqualTo(7);
        assertThat(row.issues()).containsExactly("review","5");
    }
    @Test void textAmountsAndLegacyNumericSourceRowCoercionAreNotSilentlyChanged() {
        var row = codec.decode("{\"rows\":[{\"amount\":\" ₩ -4,500 \",\"sourceRowNumber\":7.9}]}").rows().get(0);
        assertThat(row.amount().number()).isNull(); assertThat(row.amount().text()).isEqualTo(" ₩ -4,500 "); assertThat(row.sourceRowNumber()).isEqualTo(7);
    }
    @Test void missingRowsAndUnparseableJsonRetainExistingMessages() {
        assertThatThrownBy(() -> codec.decode("{\"items\":[]}")).isInstanceOf(BadRequestException.class).hasMessageContaining("rows 배열");
        assertThatThrownBy(() -> codec.decode("{invalid}")).isInstanceOf(BadRequestException.class).hasMessageContaining("JSON 형식");
        assertThatThrownBy(() -> codec.decode("\"no JSON object\"")).isInstanceOf(BadRequestException.class).hasMessageContaining("JSON 객체");
    }
}
