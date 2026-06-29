package com.playdata.calen.ledger.ai;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.dto.LedgerAiAnalysisReportResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class LedgerAiN8nClient {

    private final LedgerAiAnalysisProperties properties;

    public RemoteAnalysisResponse analyze(Object payload) {
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(properties.getConnectTimeout());
            requestFactory.setReadTimeout(properties.getReadTimeout());

            RestClient restClient = RestClient.builder()
                    .requestFactory(requestFactory)
                    .build();

            RestClient.RequestBodySpec request = restClient.post()
                    .uri(properties.getWorkflowUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);

            if (hasText(properties.getApiKey()) && hasText(properties.getApiKeyHeader())) {
                request.header(properties.getApiKeyHeader(), properties.getApiKey());
            }

            RemoteAnalysisResponse response = request.body(payload)
                    .retrieve()
                    .body(RemoteAnalysisResponse.class);

            if (response == null) {
                throw new BadRequestException("n8n AI 분석 워크플로우가 빈 응답을 반환했습니다.");
            }
            if (Boolean.FALSE.equals(response.ok())) {
                throw new BadRequestException(hasText(response.error())
                        ? response.error()
                        : "n8n AI 분석 워크플로우가 요청을 처리하지 못했습니다.");
            }
            return response;
        } catch (RestClientException exception) {
            throw new BadRequestException("n8n AI 분석 워크플로우에 연결할 수 없습니다. n8n 서버와 웹훅 설정을 확인하세요.");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteAnalysisResponse(
            Boolean ok,
            String error,
            String summary,
            List<String> highlights,
            List<String> warnings,
            @JsonAlias({"risks", "riskSignals"})
            List<String> risks,
            List<String> recommendations,
            @JsonAlias({"categoryInsights", "category_insights"})
            List<String> categoryInsights,
            @JsonAlias({"paymentInsights", "payment_insights"})
            List<String> paymentInsights,
            @JsonAlias({"trendInsights", "trend_insights"})
            List<String> trendInsights,
            @JsonAlias({"unusualSpendingInsights", "unusual_spending_insights", "anomalyInsights"})
            List<String> unusualSpendingInsights,
            @JsonAlias({"fixedCostInsights", "fixed_cost_insights", "subscriptionInsights"})
            List<String> fixedCostInsights,
            @JsonAlias({"nextPeriodForecast", "next_period_forecast", "forecast"})
            String nextPeriodForecast,
            @JsonAlias({"habitAssessment", "habit_assessment"})
            String habitAssessment,
            @JsonAlias({"report", "analysisReport", "spendingReport"})
            LedgerAiAnalysisReportResponse report
    ) {
    }
}