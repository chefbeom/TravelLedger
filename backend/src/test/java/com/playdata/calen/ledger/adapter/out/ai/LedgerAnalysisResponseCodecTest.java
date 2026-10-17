package com.playdata.calen.ledger.adapter.out.ai;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisProperties;
import com.playdata.calen.ledger.domain.policy.LedgerAiRemoteResponseValidator;
import com.playdata.calen.ledger.fixture.LedgerAnalysisFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LedgerAnalysisResponseCodecTest {
    private final ObjectMapper json=new ObjectMapper();
    private final LedgerAnalysisResponseCodec codec=new LedgerAnalysisResponseCodec(json,new LedgerAiAnalysisProperties());

    @ParameterizedTest @ValueSource(strings={"quoted","message","delta","choice-text","ollama","data-content","data-response","content","response","result","text","output","direct"})
    void allExistingResponseEnvelopesKeepTheirContent(String envelope) throws Exception {
        var root=json.createObjectNode(); String payload=LedgerAnalysisFixtures.RESULT; String body;
        switch(envelope) {
            case "quoted" -> body=json.writeValueAsString(payload);
            case "direct" -> body=payload;
            case "message","delta" -> {
                root.putArray("choices").addObject().putObject(envelope).put("content",payload); body=root.toString();
            }
            case "choice-text" -> { root.putArray("choices").addObject().put("text",payload); body=root.toString(); }
            case "ollama" -> { root.putObject("message").put("content",payload); body=root.toString(); }
            case "data-content","data-response" -> { root.putObject("data").put(envelope.substring(5),payload); body=root.toString(); }
            default -> { root.put(envelope,payload); body=root.toString(); }
        }
        var result=codec.parseAssistantContent(codec.extractAssistantContent(body));
        assertThat(result.summary()).isEqualTo("fixture summary"); assertThat(result.report().fullReport()).isEqualTo("fixture analysis");
    }

    @Test void aliasesAndAllResultFieldsReachThePurePolicyWithoutDroppingEvidence() throws Exception {
        var result=codec.parseAssistantContent("""
                {"ok":true,"summary":"fixture summary","highlights":["highlight"],"warnings":["warning"],
                 "riskSignals":["risk"],"recommendations":["recommendation"],"category_insights":["category"],
                 "payment_insights":["payment"],"trend_insights":["trend"],"anomalyInsights":["unusual"],
                 "subscriptionInsights":["fixed"],"forecast":"next","habit_assessment":"habit",
                 "analysisReport":{"keySummary":"key","fullReport":"full","averageAmountInsight":"average",
                 "notableSpending":["notable"],"regularSpending":["regular"],"abnormalSpending":["abnormal"],
                 "topPaymentMethod":"top","subscriptions":["subscription"],"fixedExpenses":["fixed report"],
                 "improvementActions":["action"],"comparisonFocus":["comparison"]},"unknown":"ignored"}
                """);
        assertThat(LedgerAiRemoteResponseValidator.requireUsable(result,"fixture")).isSameAs(result);
        assertThat(result.highlights()).containsExactly("highlight"); assertThat(result.warnings()).containsExactly("warning");
        assertThat(result.risks()).containsExactly("risk"); assertThat(result.recommendations()).containsExactly("recommendation");
        assertThat(result.categoryInsights()).containsExactly("category"); assertThat(result.paymentInsights()).containsExactly("payment");
        assertThat(result.trendInsights()).containsExactly("trend"); assertThat(result.unusualSpendingInsights()).containsExactly("unusual");
        assertThat(result.fixedCostInsights()).containsExactly("fixed"); assertThat(result.nextPeriodForecast()).isEqualTo("next");
        assertThat(result.habitAssessment()).isEqualTo("habit");
        var report=result.report(); assertThat(report.keySummary()).isEqualTo("key"); assertThat(report.fullReport()).isEqualTo("full");
        assertThat(report.averageAmountInsight()).isEqualTo("average"); assertThat(report.topPaymentMethod()).isEqualTo("top");
        assertThat(report.notableSpending()).containsExactly("notable"); assertThat(report.regularSpending()).containsExactly("regular");
        assertThat(report.abnormalSpending()).containsExactly("abnormal"); assertThat(report.subscriptions()).containsExactly("subscription");
        assertThat(report.fixedExpenses()).containsExactly("fixed report"); assertThat(report.improvementActions()).containsExactly("action");
        assertThat(report.comparisonFocus()).containsExactly("comparison");
    }

    @Test void fencedJsonAndTrailingCommasRetainTheExistingCompatibilityBehavior() throws Exception {
        var result=codec.parseAssistantContent("```json\n{\"ok\":true,\"summary\":\"fixture summary\",\"highlights\":[\"fixture\",],}\n```");
        assertThat(result.summary()).isEqualTo("fixture summary"); assertThat(result.highlights()).containsExactly("fixture");
    }

    @Test void plainTextFallbackKeepsFixedSafeTextAndNeverEchoesTheRawProviderContent() throws Exception {
        var result=codec.parseAssistantContent("private fixture unstructured response");
        assertThat(result.summary()).contains("구조화 JSON").doesNotContain("private fixture");
        assertThat(result.warnings()).hasSize(1); assertThat(result.report()).isNull();
        assertThat(LedgerAiRemoteResponseValidator.requireUsable(result,"fixture")).isSameAs(result);
    }

    @Test void nullWebhookResponseStillReachesTheEmptyResponseGuard() {
        assertThat(codec.fromWire(null)).isNull();
        assertThatThrownBy(() -> LedgerAiRemoteResponseValidator.requireUsable(codec.fromWire(null),"n8n"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("empty");
    }

    @Test void topLevelMalformedCollectionsAreNotSilentlyFilteredByTheNewModel() throws Exception {
        var result=codec.parseAssistantContent("{\"ok\":true,\"summary\":\"fixture\",\"highlights\":[null,\" \"],\"warnings\":[]}");
        assertThat(result.highlights()).containsExactly(null," ");
        assertThatThrownBy(() -> LedgerAiRemoteResponseValidator.requireUsable(result,"fixture"))
                .hasMessageContaining("expected schema");
    }
}
