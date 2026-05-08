package com.playdata.calen.ledger.ocr;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.EntryType;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

@Component
@RequiredArgsConstructor
public class LedgerOcrRemoteClient {

    private static final String OCR_API_KEY_HEADER = "X-OCR-API-Key";
    private static final String DIRECT_ANALYZE_PATH = "/analyze";

    private final LedgerOcrProperties properties;

    public RemoteAnalyzeResponse analyze(MultipartFile file, String documentType) {
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(properties.getConnectTimeout());
            requestFactory.setReadTimeout(properties.getReadTimeout());

            boolean useWorkflow = properties.isWorkflowConfigured();
            RestClient.Builder restClientBuilder = RestClient.builder()
                    .requestFactory(requestFactory);
            if (!useWorkflow) {
                restClientBuilder.baseUrl(properties.getBaseUrl());
            }
            RestClient restClient = restClientBuilder.build();

            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                            ? "receipt.jpg"
                            : file.getOriginalFilename();
                }
            };

            MultipartBodyBuilder multipart = new MultipartBodyBuilder();
            multipart.part("file", resource)
                    .filename(resource.getFilename())
                    .contentType(resolveMediaType(file));
            multipart.part("documentType", documentType == null ? "AUTO" : documentType);

            RestClient.RequestBodySpec request = restClient.post()
                    .uri(useWorkflow ? properties.getWorkflowUrl() : DIRECT_ANALYZE_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA);
            if (hasText(properties.getApiKey())) {
                request.header(OCR_API_KEY_HEADER, properties.getApiKey());
            }

            RemoteAnalyzeResponse response = request.body(multipart.build())
                    .retrieve()
                    .body(RemoteAnalyzeResponse.class);

            if (response == null) {
                throw new BadRequestException("OCR analysis server returned an empty response.");
            }
            if (!response.ok()) {
                throw new BadRequestException("OCR analysis server rejected the image.");
            }
            return response;
        } catch (IOException | RestClientException exception) {
            throw new BadRequestException("OCR analysis server is unavailable. Check the OCR service and network.");
        }
    }

    private MediaType resolveMediaType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteAnalyzeResponse(
            boolean ok,
            String error,
            String documentType,
            String rawText,
            @JsonAlias("firstEntry")
            RemoteParsedResult parsed,
            @JsonAlias("entries")
            List<RemoteParsedResult> parsedEntries,
            @JsonAlias("ocrTiming")
            Map<String, Object> timing
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteParsedResult(
            @JsonAlias("date")
            LocalDate entryDate,
            @JsonAlias("time")
            @JsonFormat(pattern = "HH:mm")
            LocalTime entryTime,
            @JsonAlias("type")
            EntryType entryType,
            @JsonAlias({"transactionTitle", "storeName", "store"})
            String title,
            String memo,
            @JsonAlias({"totalAmount", "price"})
            BigDecimal amount,
            @JsonAlias({"storeName", "store"})
            String vendor,
            String paymentMethodText,
            String categoryGroupName,
            String categoryDetailName,
            String categoryText,
            @JsonAlias("items")
            List<RemoteLineItem> lineItems,
            Double confidence,
            List<String> warnings
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteLineItem(
            @JsonAlias({"name", "title"})
            String itemName,
            BigDecimal quantity,
            String unit,
            BigDecimal price
    ) {
    }
}
