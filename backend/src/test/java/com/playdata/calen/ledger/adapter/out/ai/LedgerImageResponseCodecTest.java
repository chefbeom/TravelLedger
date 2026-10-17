package com.playdata.calen.ledger.adapter.out.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.model.LedgerImageData.ImageExtraction;
import com.playdata.calen.ledger.domain.model.LedgerImageData.ParsedImageEntry;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LedgerImageResponseCodecTest {

    @ParameterizedTest @ValueSource(strings={"entries","parsedEntries","firstEntry","parsed"})
    void aliasesMapAllFieldsIntoPureValuesWithoutLosingOriginalCurrencyOrLineItems(String rootField) throws Exception {
        var json = new ObjectMapper();
        var entry = json.readTree("""
                {"date":"2026/10/13","time":"12:34:56","type":"EXPENSE","transactionTitle":"fixture merchant",
                 "memo":"fixture memo","totalAmount":5.40,"currencyCode":"USD","foreignAmount":5.40,"store":"fixture store",
                 "paymentMethodText":"fixture cash","categoryGroupName":"fixture group","categoryDetailName":"fixture detail",
                 "categoryText":"fixture category","items":[{"productName":"fixture product","quantity":2,"unit":"ea","price":2.70}],
                 "confidence":0.8,"warnings":["row fixture"],"unrecognizedField":true}
                """);
        var root = json.createObjectNode().put("ok",true).put("documentType","receipt").put("rawText","");
        root.putArray("warnings").add("root fixture");
        if (rootField.equals("entries") || rootField.equals("parsedEntries")) root.putArray(rootField).add(entry);
        else root.set(rootField,entry);
        var result = new LedgerImageResponseCodec(json).decode(root.toString(),"AUTO",System.nanoTime());
        assertThat(result.documentType()).isEqualTo("RECEIPT");
        assertThat(result.parsed()).isEqualTo(result.parsedEntries().get(0));
        assertThat(result.parsedEntries()).singleElement().satisfies(value -> {
            assertThat(value.entryDate()).isEqualTo(LocalDate.of(2026,10,13));
            assertThat(value.entryTime()).isEqualTo(LocalTime.of(12,34));
            assertThat(value.entryType()).isEqualTo(EntryType.EXPENSE); assertThat(value.title()).isEqualTo("fixture merchant");
            assertThat(value.memo()).isEqualTo("fixture memo"); assertThat(value.vendor()).isEqualTo("fixture store");
            // The existing default JsonNode decoder removes insignificant decimal zeroes.
            assertThat(value.amount()).isEqualTo(new java.math.BigDecimal("5.4"));
            assertThat(value.foreignAmount()).isEqualTo(new java.math.BigDecimal("5.4")); assertThat(value.currencyCode()).isEqualTo("USD");
            assertThat(value.paymentMethodText()).isEqualTo("fixture cash"); assertThat(value.categoryGroupName()).isEqualTo("fixture group");
            assertThat(value.categoryDetailName()).isEqualTo("fixture detail"); assertThat(value.categoryText()).isEqualTo("fixture category");
            assertThat(value.confidence()).isEqualTo(0.8); assertThat(value.warnings()).containsExactly("row fixture","root fixture");
            assertThat(value.lineItems()).singleElement().satisfies(item -> {
                assertThat(item.itemName()).isEqualTo("fixture product"); assertThat(item.quantity()).isEqualByComparingTo("2");
                assertThat(item.unit()).isEqualTo("ea"); assertThat(item.price()).isEqualTo(new java.math.BigDecimal("2.7"));
            });
        });
    }

    @ParameterizedTest @ValueSource(strings={"direct","openai-text","openai-array","ollama"})
    void envelopesAndJsonObjectExtractionKeepQuotedBracesAndUnknownFields(String envelope) throws Exception {
        var json = new ObjectMapper(); String title = "fixture } brace \\\"quote\\\" { text";
        var payload = json.createObjectNode().put("ok",true);
        payload.putArray("entries").addObject().put("entryType","EXPENSE").put("title",title).put("amount",100).put("unknown","ignored");
        String body = payload.toString();
        String content = "prefix fixture\n```json\n"+body+"\n```\ntrailing fixture {\"ignored\":true}";
        if (!envelope.equals("direct")) {
            var root = json.createObjectNode();
            if (envelope.equals("ollama")) root.putObject("message").put("content",content);
            else {
                var message = root.putArray("choices").addObject().putObject("message");
                if (envelope.equals("openai-array")) message.putArray("content").addObject().put("type","text").put("text",content);
                else message.put("content",content);
            }
            body = root.toString();
        }
        var result = new LedgerImageResponseCodec(json).decode(body,"AUTO",System.nanoTime());
        assertThat(result.parsedEntries()).singleElement().satisfies(entry -> assertThat(entry.title()).isEqualTo(title));
    }

    @Test void malformedJsonKeepsTheExistingSafeParseError() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LedgerImageResponseCodec(new ObjectMapper()).decode("private fixture not json","AUTO",System.nanoTime()))
                .isInstanceOf(com.playdata.calen.common.exception.BadRequestException.class)
                .hasMessageContaining("JSON으로 해석하지 못했습니다").hasMessageNotContaining("private fixture");
    }

    @Test
    void buildAnalyzeResponseInfersCurrentYearAndTimeFromMonthDayTimeText() {
        LedgerImageResponseCodec client = new LedgerImageResponseCodec(new ObjectMapper());
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

        ImageExtraction response = client.decode(responseBody, "PAYMENT_CAPTURE", System.nanoTime());
        ParsedImageEntry entry = response.parsedEntries().get(0);

        assertThat(entry.entryDate()).isEqualTo(LocalDate.of(LocalDate.now().getYear(), 7, 5));
        assertThat(entry.entryTime()).isEqualTo(LocalTime.of(15, 15));
        assertThat(entry.warnings()).anySatisfy(warning -> assertThat(warning).contains("\uC5F0\uB3C4"));
    }

    @Test
    void buildAnalyzeResponseInfersDateAndTimeFromRawTextWhenEntryFieldsAreMissing() {
        LedgerImageResponseCodec client = new LedgerImageResponseCodec(new ObjectMapper());
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

        ImageExtraction response = client.decode(responseBody, "PAYMENT_CAPTURE", System.nanoTime());
        ParsedImageEntry entry = response.parsedEntries().get(0);

        assertThat(entry.entryDate()).isEqualTo(LocalDate.of(LocalDate.now().getYear(), 7, 6));
        assertThat(entry.entryTime()).isEqualTo(LocalTime.of(17, 4));
        assertThat(entry.warnings()).anySatisfy(warning -> assertThat(warning).contains("\uC5F0\uB3C4"));
    }

    @Test
    void buildAnalyzeResponseCorrectsCopiedDateFromAdjacentOrderRows() {
        LedgerImageResponseCodec client = new LedgerImageResponseCodec(new ObjectMapper());
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

        ImageExtraction response = client.decode(responseBody, "PAYMENT_CAPTURE", System.nanoTime());

        assertThat(response.parsedEntries()).extracting(ParsedImageEntry::entryDate)
                .containsExactly(
                        LocalDate.of(2026, 4, 23),
                        LocalDate.of(2026, 1, 23)
                );
        assertThat(response.parsedEntries().get(1).warnings())
                .anySatisfy(warning -> assertThat(warning).contains("OCR"));
    }

    @Test
    void buildAnalyzeResponseMapsSalesSlipProductNameAliasToLineItemName() {
        LedgerImageResponseCodec client = new LedgerImageResponseCodec(new ObjectMapper());
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

        ImageExtraction response = client.decode(responseBody, "RECEIPT", System.nanoTime());
        ParsedImageEntry entry = response.parsedEntries().get(0);

        assertThat(entry.lineItems()).hasSize(1);
        assertThat(entry.lineItems().get(0).itemName()).isEqualTo("Lost Ark 80,000 Royal Crystal");
        assertThat(entry.entryDate()).isEqualTo(LocalDate.of(2026, 6, 24));
        assertThat(entry.entryTime()).isEqualTo(LocalTime.of(15, 15));
    }

    @Test
    void buildAnalyzeResponseUsesMatchingRawTextRowDateForMultipleEntries() {
        LedgerImageResponseCodec client = new LedgerImageResponseCodec(new ObjectMapper());
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

        ImageExtraction response = client.decode(responseBody, "PAYMENT_CAPTURE", System.nanoTime());

        assertThat(response.parsedEntries()).extracting(ParsedImageEntry::entryDate)
                .containsExactly(
                        LocalDate.of(2026, 1, 23),
                        LocalDate.of(2025, 12, 15),
                        LocalDate.of(2025, 11, 7)
                );
        assertThat(response.parsedEntries()).extracting(ParsedImageEntry::entryTime)
                .containsExactly(null, null, null);
    }

    @Test
    void buildAnalyzeResponseCorrectsShoppingOrdersMisclassifiedAsIncome() {
        LedgerImageResponseCodec client = new LedgerImageResponseCodec(new ObjectMapper());
        String responseBody = """
                {
                  "ok": true,
                  "documentType": "AUTO",
                  "rawText": "주문 정보 최근 주문 3건 주문일자 주문 상품정보 상품금액 배송비 주문상태 구매확정",
                  "entries": [
                    {
                      "date": "2026-04-23",
                      "entryType": "INCOME",
                      "title": "비바스 내추럴99% 시카 천연샴푸1000g_2개",
                      "memo": "상품금액 25,020원 / 구매확정",
                      "amount": 25020,
                      "warnings": []
                    },
                    {
                      "date": "2026-01-23",
                      "entryType": "INCOME",
                      "title": "초강력 무선 에어건 Aero X10 130000rpm",
                      "memo": "상품금액 37,370원 / 구매확정",
                      "amount": 37370,
                      "warnings": []
                    }
                  ],
                  "warnings": []
                }
                """;

        ImageExtraction response = client.decode(responseBody, "AUTO", System.nanoTime());

        assertThat(response.parsedEntries()).extracting(ParsedImageEntry::entryType)
                .containsExactly(EntryType.EXPENSE, EntryType.EXPENSE);
        assertThat(response.parsedEntries().get(0).warnings())
                .anySatisfy(warning -> assertThat(warning).contains("거래 구분").contains("지출"));
    }

    @Test
    void buildAnalyzeResponseClassifiesMixedIncomeAndPurchaseRowsIndependently() {
        LedgerImageResponseCodec client = new LedgerImageResponseCodec(new ObjectMapper());
        String responseBody = """
                {
                  "ok": true,
                  "documentType": "AUTO",
                  "rawText": "주문 정보 주문일자 상품금액 구매확정 / 7월 급여 입금되었습니다 3,000,000원",
                  "entries": [
                    {
                      "date": "2026-07-10",
                      "entryType": "INCOME",
                      "title": "7월 급여",
                      "memo": "급여 입금되었습니다",
                      "amount": 3000000,
                      "warnings": []
                    },
                    {
                      "date": "2026-04-23",
                      "entryType": "INCOME",
                      "title": "비바스 내추럴99% 시카 천연샴푸1000g_2개",
                      "memo": "25,020원",
                      "amount": 25020,
                      "warnings": []
                    }
                  ],
                  "warnings": []
                }
                """;

        ImageExtraction response = client.decode(responseBody, "AUTO", System.nanoTime());

        assertThat(response.parsedEntries()).extracting(ParsedImageEntry::entryType)
                .containsExactly(EntryType.INCOME, EntryType.EXPENSE);
    }
    @Test
    void buildAnalyzeResponseReadsOllamaMessageContent() {
        LedgerImageResponseCodec client = new LedgerImageResponseCodec(new ObjectMapper());
        String responseBody = """
                {
                  "model": "llama3.2-vision",
                  "message": {
                    "role": "assistant",
                    "content": "{\\"ok\\":true,\\"documentType\\":\\"AUTO\\",\\"rawText\\":\\"Ollama capture\\",\\"entries\\":[{\\"entryType\\":\\"EXPENSE\\",\\"title\\":\\"Coffee\\",\\"amount\\":4900,\\"items\\":[],\\"warnings\\":[]}],\\"warnings\\":[]}"
                  }
                }
                """;

        ImageExtraction response = client.decode(responseBody, "AUTO", System.nanoTime());

        assertThat(response.parsedEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.title()).isEqualTo("Coffee");
            assertThat(entry.amount()).isEqualByComparingTo("4900");
            assertThat(entry.entryType()).isEqualTo(EntryType.EXPENSE);
        });
    }
}
