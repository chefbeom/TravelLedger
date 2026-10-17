package com.playdata.calen.ledger.adapter.out.serialization;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.ledger.adapter.support.PreparedLedgerWorkbookFormat.WorkbookPayload;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.SourceReference;
import java.util.*;
import org.junit.jupiter.api.Test;

class JacksonPreparedLedgerWorkbookAdapterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JacksonPreparedLedgerWorkbookAdapter adapter = new JacksonPreparedLedgerWorkbookAdapter(json);
    @Test void existingPersistedInputLayoutAndSourceMapsRemainRestartCompatible() throws Exception {
        String legacy = """
                {"fileName":"old.xlsx","sheetName":"Sheet","includedRows":1,"skippedRows":2,
                 "knownChoices":{"paymentMethods":["Card"],"categoryDetailsByGroup":{"Food":["Cafe"]},"paymentMethodsPayload":["Card"],"categoriesPayload":[]},
                 "sourceReferencesById":{"row-1":{"sourceId":"row-1","sheetName":"Sheet","rowNumber":7}},
                 "sourceReferencesByLocation":{"Sheet\\u00007":{"sourceId":"row-1","sheetName":"Sheet","rowNumber":7}},
                 "payload":{"task":"ledger_excel_import","cells":["untrusted"]}}
                """;
        var workbook = adapter.read(legacy);
        assertThat(workbook.fileName()).isEqualTo("old.xlsx"); assertThat(workbook.choices().paymentMethods()).containsExactly("Card");
        assertThat(workbook.choices().categoryDetailsByGroup()).isEqualTo(Map.of("Food",Set.of("Cafe")));
        assertThat(workbook.sourcesById().get("row-1")).isEqualTo(new SourceReference("row-1","Sheet",7));
        assertThat(workbook.sourcesByLocation().get("Sheet\u00007")).isEqualTo(workbook.sourcesById().get("row-1"));
        assertThat(workbook.payloadJson()).isEqualTo("{\"task\":\"ledger_excel_import\",\"cells\":[\"untrusted\"]}");
        assertThat(json.readTree(json.writeValueAsString(json.readValue(legacy,WorkbookPayload.class)))).isEqualTo(json.readTree(legacy));
    }
    @Test void corruptAndUnknownPreparedFieldsRetainTheExistingFailureContract() {
        assertThatThrownBy(() -> adapter.read("not JSON")).hasMessage("Excel 분석 입력을 복구할 수 없습니다.");
        assertThatThrownBy(() -> adapter.read("{\"unknown\":true}")).hasMessage("Excel 분석 입력을 복구할 수 없습니다.");
    }
}
