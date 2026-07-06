package com.playdata.calen.ledger.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisProperties;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteAnalyzeResponse;
import com.playdata.calen.ledger.ocr.LedgerOcrRemoteClient.RemoteParsedResult;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class LedgerOcrRemoteClientTest {

    @Test
    void buildAnalyzeResponseInfersCurrentYearAndTimeFromMonthDayTimeText() {
        LedgerOcrRemoteClient client = new LedgerOcrRemoteClient(new LedgerAiAnalysisProperties(), new ObjectMapper());
        String responseBody = """
                {
                  "ok": true,
                  "documentType": "PAYMENT_CAPTURE",
                  "rawText": "네이버페이 결제내역",
                  "entries": [
                    {
                      "date": "7. 5. 15:15 결제",
                      "time": null,
                      "entryType": "EXPENSE",
                      "title": "네이버페이 : 웹툰·시리즈 쿠키 59개",
                      "memo": "결제완료 / 7. 5. 15:15 결제 / 4,900원",
                      "amount": 4900,
                      "vendor": "네이버페이",
                      "paymentMethodText": "",
                      "categoryGroupName": "",
                      "categoryDetailName": "",
                      "categoryText": "",
                      "items": [],
                      "confidence": 0.9,
                      "warnings": ["이미지 내 날짜 정보가 불분명하여 모든 항목의 시간은 null로 처리되었습니다. 정확한 거래일시를 확인해 주세요."]
                    }
                  ],
                  "warnings": ["이미지 내 날짜 정보가 불분명하여 모든 항목의 시간은 null로 처리되었습니다. 정확한 거래일시를 확인해 주세요."]
                }
                """;

        RemoteAnalyzeResponse response = client.buildAnalyzeResponse(responseBody, "PAYMENT_CAPTURE", System.nanoTime());
        RemoteParsedResult entry = response.parsedEntries().get(0);

        assertThat(entry.entryDate()).isEqualTo(LocalDate.of(LocalDate.now().getYear(), 7, 5));
        assertThat(entry.entryTime()).isEqualTo(LocalTime.of(15, 15));
        assertThat(entry.warnings()).anySatisfy(warning -> assertThat(warning).contains("연도"));
        assertThat(entry.warnings()).noneSatisfy(warning -> assertThat(warning).contains("시간은 null"));
    }
    @Test
    void buildAnalyzeResponseInfersDateAndTimeFromRawTextWhenEntryFieldsAreMissing() {
        LedgerOcrRemoteClient client = new LedgerOcrRemoteClient(new LedgerAiAnalysisProperties(), new ObjectMapper());
        String responseBody = """
                {
                  "ok": true,
                  "documentType": "PAYMENT_CAPTURE",
                  "rawText": "payment completed nebibe one month subscription 4,900 KRW | 7. 6. 17:04 paid",
                  "entries": [
                    {
                      "entryType": "EXPENSE",
                      "title": "nebibe one month subscription",
                      "memo": "nebibe one month subscription(4,900 KRW)",
                      "amount": 4900,
                      "vendor": "",
                      "paymentMethodText": "",
                      "categoryGroupName": "",
                      "categoryDetailName": "",
                      "categoryText": "",
                      "items": [],
                      "confidence": 0.9,
                      "warnings": []
                    }
                  ],
                  "warnings": []
                }
                """;

        RemoteAnalyzeResponse response = client.buildAnalyzeResponse(responseBody, "PAYMENT_CAPTURE", System.nanoTime());
        RemoteParsedResult entry = response.parsedEntries().get(0);

        assertThat(entry.entryDate()).isEqualTo(LocalDate.of(LocalDate.now().getYear(), 7, 6));
        assertThat(entry.entryTime()).isEqualTo(LocalTime.of(17, 4));
        assertThat(entry.warnings()).anySatisfy(warning -> assertThat(warning).contains("\uC5F0\uB3C4"));
    }
    @Test
    void buildAnalyzeResponseMapsSalesSlipProductNameAliasToLineItemName() {
        LedgerOcrRemoteClient client = new LedgerOcrRemoteClient(new LedgerAiAnalysisProperties(), new ObjectMapper());
        String responseBody = """
                {
                  "ok": true,
                  "documentType": "RECEIPT",
                  "rawText": "SALES SLIP ITEM Lost Ark 80,000 Royal Crystal TOTAL 80000",
                  "entries": [
                    {
                      "date": "2026-06-24",
                      "time": "15:15",
                      "entryType": "EXPENSE",
                      "title": "SALES SLIP",
                      "memo": "ITEM Lost Ark 80,000 Royal Crystal / TOTAL 80,000",
                      "amount": 80000,
                      "vendor": "Hyundai Card",
                      "paymentMethodText": "Hyundai Card",
                      "categoryGroupName": "",
                      "categoryDetailName": "",
                      "categoryText": "",
                      "items": [{"productName":"Lost Ark 80,000 Royal Crystal","price":80000}],
                      "confidence": 0.9,
                      "warnings": []
                    }
                  ],
                  "warnings": []
                }
                """;

        RemoteAnalyzeResponse response = client.buildAnalyzeResponse(responseBody, "RECEIPT", System.nanoTime());
        RemoteParsedResult entry = response.parsedEntries().get(0);

        assertThat(entry.lineItems()).hasSize(1);
        assertThat(entry.lineItems().get(0).itemName()).isEqualTo("Lost Ark 80,000 Royal Crystal");
        assertThat(entry.entryDate()).isEqualTo(LocalDate.of(2026, 6, 24));
        assertThat(entry.entryTime()).isEqualTo(LocalTime.of(15, 15));
    }
}