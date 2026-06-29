package com.playdata.calen.ledger.ai;

import com.playdata.calen.common.exception.BadRequestException;
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
            throw new BadRequestException("n8n AI 遺꾩꽍 ?뚰겕?뚮줈?곗뿉 ?곌껐?????놁뒿?덈떎. n8n ?쒕쾭? ?뱁썒 ?ㅼ젙???뺤씤?섏꽭??");
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
