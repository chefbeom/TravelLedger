package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisReportResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerAiRemoteResponseValidatorTest {

    @Test
    void acceptsResponseWithSummaryOnly() {
        LedgerAiRemoteResponse response = new LedgerAiRemoteResponse(
                true,
                null,
                "이번 달 지출은 식비 중심입니다.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null
        );

        assertThat(LedgerAiRemoteResponseValidator.requireUsable(response, "LM Studio"))
                .isSameAs(response);
    }

    @Test
    void acceptsResponseWithReportOnly() {
        LedgerAiRemoteResponse response = new LedgerAiRemoteResponse(
                true,
                null,
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                new LedgerAiAnalysisReportResponse(
                        "핵심 요약",
                        "",
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                )
        );

        assertThat(LedgerAiRemoteResponseValidator.requireUsable(response, "n8n"))
                .isSameAs(response);
    }

    @Test
    void rejectsNullResponse() {
        assertThatThrownBy(() -> LedgerAiRemoteResponseValidator.requireUsable(null, "n8n"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("n8n AI 분석 응답이 비어 있습니다.");
    }

    @Test
    void rejectsProviderFailureWithProviderError() {
        LedgerAiRemoteResponse response = new LedgerAiRemoteResponse(
                false,
                "provider failed",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null
        );

        assertThatThrownBy(() -> LedgerAiRemoteResponseValidator.requireUsable(response, "LM Studio"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("provider failed");
    }

    @Test
    void rejectsEmptySuccessResponse() {
        LedgerAiRemoteResponse response = new LedgerAiRemoteResponse(
                true,
                null,
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                LedgerAiAnalysisReportResponse.empty()
        );

        assertThatThrownBy(() -> LedgerAiRemoteResponseValidator.requireUsable(response, "LM Studio"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("LM Studio AI 분석 응답에 사용할 수 있는 분석 내용이 없습니다.");
    }
}