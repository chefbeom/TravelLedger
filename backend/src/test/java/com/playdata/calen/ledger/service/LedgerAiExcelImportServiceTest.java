package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisProperties;
import com.playdata.calen.ledger.dto.LedgerExcelPreviewResponse;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.lang.reflect.Method;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class LedgerAiExcelImportServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CategoryGroupRepository categoryGroupRepository = Mockito.mock(CategoryGroupRepository.class);
    private final CategoryDetailRepository categoryDetailRepository = Mockito.mock(CategoryDetailRepository.class);
    private final PaymentMethodRepository paymentMethodRepository = Mockito.mock(PaymentMethodRepository.class);
    private final LedgerAiExcelImportService service = new LedgerAiExcelImportService(
            Mockito.mock(AppUserService.class),
            new LedgerAiAnalysisProperties(),
            objectMapper,
            categoryGroupRepository,
            categoryDetailRepository,
            paymentMethodRepository
    );

    @Test
    void previewAcceptsOnlyRowsLinkedToOriginalWorkbookSourceIds() throws Exception {
        prepareKnownChoices();
        try (XSSFWorkbook workbook = workbookWithOneTransaction()) {
            Object workbookPayload = ReflectionTestUtils.invokeMethod(
                    service, "buildWorkbookPayload", 7L, "ledger.xlsx", workbook
            );
            JsonNode payload = payloadOf(workbookPayload);
            assertThat(payload.at("/sheets/0/rows/1/sourceId").asText()).isEqualTo("sheet-0-row-2");

            String aiResponse = """
                    {
                      "rows": [
                        {
                          "sourceId": "sheet-0-row-2",
                          "entryDate": "2026-07-13",
                          "entryTime": "10:30",
                          "title": "Coffee",
                          "memo": "original row",
                          "amount": 4500,
                          "entryType": "EXPENSE",
                          "paymentMethodName": "",
                          "categoryGroupName": "",
                          "categoryDetailName": ""
                        },
                        {
                          "sourceId": "invented-row",
                          "entryDate": "2026-07-13",
                          "title": "Invented transaction",
                          "amount": 9999,
                          "entryType": "EXPENSE"
                        }
                      ]
                    }
                    """;

            LedgerExcelPreviewResponse response = ReflectionTestUtils.invokeMethod(
                    service, "buildPreviewResponse", workbookPayload, aiResponse
            );

            assertThat(response.rows()).hasSize(1);
            assertThat(response.rows().get(0).sourceRowNumber()).isEqualTo(2);
            assertThat(response.rows().get(0).title()).isEqualTo("Coffee");
            assertThat(response.notes()).anyMatch(note -> note.contains("원본 Excel 행과 연결"));
        }
    }

    @Test
    void extractsOpenAiContentArrayResponses() {
        String response = """
                {"choices":[{"message":{"content":[{"type":"output_text","text":"{\\\"rows\\\":[]}"}]}}]}
                """;

        String content = ReflectionTestUtils.invokeMethod(service, "extractAssistantContent", response);

        assertThat(content).isEqualTo("{\"rows\":[]}");
    }

    private void prepareKnownChoices() {
        when(categoryGroupRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(7L)).thenReturn(List.of());
        when(paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(7L)).thenReturn(List.of());
    }

    private XSSFWorkbook workbookWithOneTransaction() {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Transactions");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Date");
        header.createCell(1).setCellValue("Title");
        header.createCell(2).setCellValue("Amount");
        Row transaction = sheet.createRow(1);
        transaction.createCell(0).setCellValue("2026-07-13");
        transaction.createCell(1).setCellValue("Coffee");
        transaction.createCell(2).setCellValue(4500);
        return workbook;
    }

    private JsonNode payloadOf(Object workbookPayload) throws Exception {
        Method payload = workbookPayload.getClass().getDeclaredMethod("payload");
        payload.setAccessible(true);
        return (JsonNode) payload.invoke(workbookPayload);
    }
}