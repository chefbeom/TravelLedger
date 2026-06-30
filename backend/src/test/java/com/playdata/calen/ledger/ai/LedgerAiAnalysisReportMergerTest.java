package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.playdata.calen.ledger.dto.LedgerAiAnalysisReportResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerAiAnalysisReportMergerTest {

    private final LedgerAiAnalysisReportMerger merger = new LedgerAiAnalysisReportMerger(new LedgerAiAnalysisTextSanitizer());

    @Test
    void remoteReportFieldsOverrideFallbackReport() {
        LedgerAiAnalysisReportResponse fallback = report("fallback key", "fallback full", List.of("fallback action"));
        LedgerAiRemoteResponse remote = new LedgerAiRemoteResponse(
                true, null, "remote summary", List.of("remote highlight"), List.of(), List.of(), List.of("remote recommendation"),
                List.of(), List.of(), List.of("remote trend"), List.of(), List.of(), null, null,
                report("remote key", "", List.of("remote action"))
        );

        LedgerAiAnalysisReportResponse result = merger.merge(fallback, remote);

        assertThat(result.keySummary()).isEqualTo("remote key");
        assertThat(result.fullReport()).isEqualTo("fallback full");
        assertThat(result.notableSpending()).containsExactly("remote highlight");
        assertThat(result.improvementActions()).containsExactly("remote action");
        assertThat(result.comparisonFocus()).containsExactly("remote trend");
    }

    @Test
    void remoteSummaryAndListsFillBlankRemoteReportFields() {
        LedgerAiAnalysisReportResponse fallback = report("fallback key", "fallback full", List.of("fallback action"));
        LedgerAiRemoteResponse remote = new LedgerAiRemoteResponse(
                true, null, "provider summary only", List.of("provider highlight"), List.of("provider warning"), List.of(), List.of("provider recommendation"),
                List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null
        );

        LedgerAiAnalysisReportResponse result = merger.merge(fallback, remote);

        assertThat(result.keySummary()).isEqualTo("provider summary only");
        assertThat(result.fullReport()).isEqualTo("fallback full");
        assertThat(result.notableSpending()).containsExactly("provider highlight");
        assertThat(result.abnormalSpending()).containsExactly("provider warning");
        assertThat(result.improvementActions()).containsExactly("provider recommendation");
    }

    private LedgerAiAnalysisReportResponse report(String keySummary, String fullReport, List<String> improvementActions) {
        return new LedgerAiAnalysisReportResponse(
                keySummary,
                fullReport,
                "average insight",
                List.of("fallback notable"),
                List.of("fallback regular"),
                List.of("fallback abnormal"),
                "fallback payment",
                List.of("fallback subscription"),
                List.of("fallback fixed"),
                improvementActions,
                List.of("fallback comparison")
        );
    }
}