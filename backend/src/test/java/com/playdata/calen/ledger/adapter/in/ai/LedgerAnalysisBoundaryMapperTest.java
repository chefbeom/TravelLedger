package com.playdata.calen.ledger.adapter.in.ai;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.playdata.calen.ledger.adapter.in.ai.LedgerAnalysisPreparedData.LedgerAiN8nPayload;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerAnalysisData.Report;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisReportResponse;
import com.playdata.calen.ledger.fixture.LedgerAnalysisFixtures;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LedgerAnalysisBoundaryMapperTest {
    @ParameterizedTest @ValueSource(booleans={true,false})
    void everyNestedFieldRetainsJsonBytesScalesDatesAndPersistedV2Compatibility(boolean timestampDates) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules().configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,timestampDates);
        var legacy = LedgerAnalysisFixtures.legacy(); var request = LedgerAnalysisBoundaryMapper.toRequest(legacy);
        String before = json.writeValueAsString(legacy);
        assertThat(json.writeValueAsString(request)).isEqualTo(before).doesNotContain("ownerId","clientRequestId","apiKey");
        if (!timestampDates) assertThat(json.readTree(before)).isEqualTo(json.readTree(LedgerAnalysisFixtures.INPUT));
        assertThat(LedgerAnalysisBoundaryMapper.toRequest(json.readValue(before,LedgerAiN8nPayload.class))).isEqualTo(request);
        assertThat(request.overview().expense()).isEqualTo(legacy.overview().expense());
        assertThat(request.recurringExpenseCandidates().get(0).averageAmount()).isEqualTo(legacy.recurringExpenseCandidates().get(0).averageAmount());
    }

    @Test void allExistingEnumCodesPreserveTheirWireNames() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        for (var mode : LedgerAiAnalysisMode.values()) for (var period : LedgerAiAnalysisPeriod.values()) for (var preset : LedgerAiComparisonPreset.values()) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(LedgerAnalysisFixtures.INPUT);
            node.put("mode",mode.name()).put("periodType",period.name()).put("comparisonPreset",preset.name());
            var legacy = json.treeToValue(node,LedgerAiN8nPayload.class);
            assertThat(json.writeValueAsString(LedgerAnalysisBoundaryMapper.toRequest(legacy))).isEqualTo(json.writeValueAsString(legacy));
        }
    }

    @Test void nullOptionalValuesAndEmptyListsAreNotInventedOrNormalized() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var legacy = json.readValue("{\"expenseEntries\":[],\"comparisonExpenseEntries\":[null]}",LedgerAiN8nPayload.class);
        assertThat(LedgerAnalysisBoundaryMapper.toRequest(null)).isNull();
        assertThat(json.writeValueAsString(LedgerAnalysisBoundaryMapper.toRequest(legacy))).isEqualTo(json.writeValueAsString(legacy));
    }

    @Test void pureReportKeepsThePreviousDtoNullAndBlankFilteringContract() throws Exception {
        var json = new ObjectMapper(); var values = Arrays.asList(null," "," kept ");
        var report = new Report(null,null,null,values,null,values,null,null,values,values,values);
        var legacy = new LedgerAiAnalysisReportResponse(null,null,null,values,null,values,null,null,values,values,values);
        assertThat(json.writeValueAsString(report)).isEqualTo(json.writeValueAsString(legacy));
        assertThat(report.notableSpending()).containsExactly(" kept ");
    }
}
