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
                  "rawText": "naver pay payment history",
                  "entries": [
                    {
                      "date": "7. 5. 15:15 paid",
                      "time": null,
                      "entryType": "EXPENSE",
                      "title": "Naver Pay : Webtoon Series Cookie 59",
                      "memo": "completed / 7. 5. 15:15 paid / 4,900 KRW",
                      "amount": 4900,
                      "vendor": "Naver Pay",
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

        assertThat(entry.entryDate()).isEqualTo(LocalDate.of(LocalDate.now().getYear(), 7, 5));
        assertThat(entry.entryTime()).isEqualTo(LocalTime.of(15, 15));
        assertThat(entry.warnings()).anySatisfy(warning -> assertThat(warning).contains("\uC5F0\uB3C4"));
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
    void buildAnalyzeResponseCorrectsCopiedDateFromAdjacentOrderRows() {
        LedgerOcrRemoteClient client = new LedgerOcrRemoteClient(new LedgerAiAnalysisProperties(), new ObjectMapper());
        String responseBody = """
                {
                  "ok": true,
                  "documentType": "PAYMENT_CAPTURE",
                  "rawText": "Order info recent orders\\nOrder date product amount seller status\\n2026-04-23 (20260423061328376)\\nVivas natural cica shampoo 1000g 2ea\\n25,020 KRW (1)\\nfree haeduen0\\nconfirmed\\n2026-01-23 (2026012303520621)\\nAero X10 130000rpm wireless air duster\\n37,370 KRW (1)\\nfree eunkeon\\nconfirmed",
                  "entries": [
                    {
                      "date": "2026-04-23",
                      "entryType": "EXPENSE",
                      "title": "Shop : Vivas natural cica shampoo 1000g 2ea",
                      "memo": "Vivas natural cica shampoo 1000g 2ea(25,020 KRW)",
                      "amount": 25020,
                      "items": [{"name":"Vivas natural cica shampoo 1000g 2ea","price":25020}],
                      "warnings": []
                    },
                    {
                      "date": "2026-04-23",
                      "entryType": "EXPENSE",
                      "title": "Shop : Aero X10 130000rpm wireless air duster",
                      "memo": "Aero X10 130000rpm wireless air duster(37,370 KRW)",
                      "amount": 37370,
                      "items": [{"name":"Aero X10 130000rpm wireless air duster","price":37370}],
                      "warnings": []
                    }
                  ],
                  "warnings": []
                }
                """;

        RemoteAnalyzeResponse response = client.buildAnalyzeResponse(responseBody, "PAYMENT_CAPTURE", System.nanoTime());

        assertThat(response.parsedEntries()).extracting(RemoteParsedResult::entryDate)
                .containsExactly(
                        LocalDate.of(2026, 4, 23),
                        LocalDate.of(2026, 1, 23)
                );
        assertThat(response.parsedEntries().get(1).warnings())
                .anySatisfy(warning -> assertThat(warning).contains("OCR"));
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

    @Test
    void buildAnalyzeResponseUsesMatchingRawTextRowDateForMultipleEntries() {
        LedgerOcrRemoteClient client = new LedgerOcrRemoteClient(new LedgerAiAnalysisProperties(), new ObjectMapper());
        String responseBody = """
                {
                  "ok": true,
                  "documentType": "PAYMENT_CAPTURE",
                  "rawText": "2026.01.23 Aero X10 13000rpm wireless air duster 37,370 KRW (1)\\n2025.12.15 [CGV] photo card 50,000 KRW voucher 90,000 KRW (2)\\n2025.11.07 Kerasys repair shampoo 1L plus treatment 8,950 KRW (1)",
                  "entries": [
                    {
                      "entryType": "EXPENSE",
                      "title": "Aero X10 13000rpm wireless air duster",
                      "memo": "Aero X10 13000rpm wireless air duster(37,370 KRW)",
                      "amount": 37370,
                      "items": [{"name":"Aero X10 13000rpm wireless air duster","price":37370}],
                      "warnings": []
                    },
                    {
                      "entryType": "EXPENSE",
                      "title": "[CGV] photo card 50,000 KRW voucher",
                      "memo": "[CGV] photo card 50,000 KRW voucher(90,000 KRW)",
                      "amount": 90000,
                      "items": [{"name":"[CGV] photo card 50,000 KRW voucher","price":90000}],
                      "warnings": []
                    },
                    {
                      "entryType": "EXPENSE",
                      "title": "Kerasys repair shampoo 1L plus treatment",
                      "memo": "Kerasys repair shampoo 1L plus treatment(8,950 KRW)",
                      "amount": 8950,
                      "items": [{"name":"Kerasys repair shampoo 1L plus treatment","price":8950}],
                      "warnings": []
                    }
                  ],
                  "warnings": []
                }
                """;

        RemoteAnalyzeResponse response = client.buildAnalyzeResponse(responseBody, "PAYMENT_CAPTURE", System.nanoTime());

        assertThat(response.parsedEntries()).extracting(RemoteParsedResult::entryDate)
                .containsExactly(
                        LocalDate.of(2026, 1, 23),
                        LocalDate.of(2025, 12, 15),
                        LocalDate.of(2025, 11, 7)
                );
        assertThat(response.parsedEntries()).extracting(RemoteParsedResult::entryTime)
                .containsExactly(null, null, null);
    }
}