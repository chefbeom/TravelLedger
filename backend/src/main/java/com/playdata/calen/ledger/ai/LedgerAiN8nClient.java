package com.playdata.calen.ledger.ai;

import com.playdata.calen.common.exception.BadRequestException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class LedgerAiN8nClient {

    private final LedgerAiAnalysisProperties properties;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public LedgerAiRemoteResponse analyze(Object payload) {
        Timer.Sample workflowTimer = startExternalWorkflowTimer();
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

            LedgerAiRemoteResponse response = request.body(payload)
                    .retrieve()
                    .body(LedgerAiRemoteResponse.class);

            LedgerAiRemoteResponse validated = LedgerAiRemoteResponseValidator.requireUsable(response, "n8n");
            recordExternalWorkflow(workflowTimer, "ledger-ai-n8n", "success");
            return validated;

        } catch (RestClientException exception) {
            recordExternalWorkflow(workflowTimer, "ledger-ai-n8n", "failure");
            throw new BadRequestException("n8n AI 분석 워크플로에 연결할 수 없습니다. n8n 서버와 webhook 설정을 확인하세요.");
        } catch (RuntimeException exception) {
            recordExternalWorkflow(workflowTimer, "ledger-ai-n8n", "failure");
            throw exception;
        }
    }

    private Timer.Sample startExternalWorkflowTimer() {
        return meterRegistry == null ? null : Timer.start(meterRegistry);
    }

    private void recordExternalWorkflow(Timer.Sample sample, String workflow, String status) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("calen.external.workflow.requests")
                .description("External workflow/client requests")
                .tag("workflow", workflow)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
        if (sample != null) {
            sample.stop(Timer.builder("calen.external.workflow.request")
                    .description("External workflow/client request duration")
                    .tag("workflow", workflow)
                    .tag("status", status)
                    .register(meterRegistry));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
