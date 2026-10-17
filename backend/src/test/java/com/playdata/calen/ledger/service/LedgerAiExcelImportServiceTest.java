package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisProperties;
import com.playdata.calen.ledger.adapter.out.ai.LedgerWorkbookResponseCodec;
import com.playdata.calen.ledger.adapter.out.serialization.JacksonPreparedLedgerWorkbookAdapter;
import com.playdata.calen.ledger.application.service.AnalyzeLedgerWorkbookService;
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
    private final LedgerWorkbookResponseCodec responseCodec = new LedgerWorkbookResponseCodec(objectMapper);
    private final java.util.concurrent.atomic.AtomicReference<String> remoteResponse = new java.util.concurrent.atomic.AtomicReference<>();
    private final LedgerAiExcelImportService service = new LedgerAiExcelImportService(
            Mockito.mock(AppUserService.class),
            new LedgerAiAnalysisProperties(),
            objectMapper,
            categoryGroupRepository,
            categoryDetailRepository,
            paymentMethodRepository,
            new AnalyzeLedgerWorkbookService(new JacksonPreparedLedgerWorkbookAdapter(objectMapper), payload -> responseCodec.decode(remoteResponse.get()))
    );

    @Test
    void previewAcceptsOnlyRowsLinkedToOriginalWorkbookSourceIds() throws Exception {
        prepareKnownChoices();
        try (XSSFWorkbook workbook = workbookWithOneTransaction()) {
            Object workbookPayload = ReflectionTestUtils.invokeMethod(
                    service, "buildWorkbookPayload", 7L, "ledger.xlsx", workbook
            );
            workbookPayload = objectMapper.readValue(objectMapper.writeValueAsString(workbookPayload), workbookPayload.getClass());
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

            remoteResponse.set(aiResponse);
            LedgerExcelPreviewResponse response = service.analyzePreparedInput(objectMapper.writeValueAsString(workbookPayload));

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

        String content = ReflectionTestUtils.invokeMethod(responseCodec, "extractAssistantContent", response);

        assertThat(content).isEqualTo("{\"rows\":[]}");
    }

    private void prepareKnownChoices() {
        when(categoryGroupRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(7L)).thenReturn(List.of());
        when(paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(7L)).thenReturn(List.of());
    }

    @Test void preparationRetainsThe260RowLimitAcrossSheetsAndStableSourceIds() throws Exception {
        prepareKnownChoices();
        try (var workbook = new XSSFWorkbook()) {
            for (String name : List.of("First","Second")) {
                var sheet = workbook.createSheet(name);
                for (int i=0;i<200;i++) sheet.createRow(i).createCell(0).setCellValue("row "+i);
            }
            Object prepared = ReflectionTestUtils.invokeMethod(service,"buildWorkbookPayload",7L,"book.xlsx",workbook);
            var stored = objectMapper.valueToTree(prepared);
            assertThat(stored.path("includedRows").asInt()).isEqualTo(260);
            assertThat(stored.at("/payload/sheets/0/rows").size()).isEqualTo(200);
            assertThat(stored.at("/payload/sheets/1/rows").size()).isEqualTo(60);
            assertThat(stored.at("/payload/sheets/1/rows/59/sourceId").asText()).isEqualTo("sheet-1-row-60");
            assertThat(stored.path("sourceReferencesById").size()).isEqualTo(260);
        }
    }

    @Test void preparationKeeps28CellsSkipsOutOfRangeOnlyRowsAndPreservesFormattedDates() throws Exception {
        prepareKnownChoices();
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet"); sheet.createRow(0).createCell(28).setCellValue("outside");
            var row = sheet.createRow(1); var cell = row.createCell(0); cell.setCellValue(java.time.LocalDate.of(2026,10,16));
            var style = workbook.createCellStyle(); style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd")); cell.setCellStyle(style);
            row.createCell(27).setCellValue("keep"); row.createCell(28).setCellValue("drop");
            Object prepared = ReflectionTestUtils.invokeMethod(service,"buildWorkbookPayload",7L,"book.xlsx",workbook);
            var stored = objectMapper.valueToTree(prepared);
            assertThat(stored.path("includedRows").asInt()).isEqualTo(1); assertThat(stored.path("skippedRows").asInt()).isEqualTo(1);
            assertThat(stored.at("/payload/sheets/0/rows/0/cells").size()).isEqualTo(28);
            assertThat(stored.at("/payload/sheets/0/rows/0/cells/0").asText()).isEqualTo("2026-10-16");
            assertThat(stored.at("/payload/sheets/0/rows/0/cells/27").asText()).isEqualTo("keep");
        }
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
