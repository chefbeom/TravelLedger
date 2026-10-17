package com.playdata.calen.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisProperties;
import com.playdata.calen.ledger.ai.LedgerAiFeature;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.dto.LedgerExcelPreviewResponse;
import com.playdata.calen.ledger.adapter.support.PreparedLedgerWorkbookFormat.*;
import com.playdata.calen.ledger.adapter.support.LedgerWorkbookPreviewMapper;
import com.playdata.calen.ledger.application.port.in.AnalyzeLedgerWorkbookUseCase;
import com.playdata.calen.ledger.domain.model.LedgerWorkbookData.SourceReference;
import com.playdata.calen.ledger.domain.policy.LedgerWorkbookPreviewPolicy;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class LedgerAiExcelImportService {

    private static final long MAX_EXCEL_FILE_SIZE_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_WORKBOOK_ROWS = 260;
    private static final int MAX_CELLS_PER_ROW = 28;

    private final AppUserService appUserService;
    private final LedgerAiAnalysisProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final AnalyzeLedgerWorkbookUseCase workbookAnalysis;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LedgerExcelPreviewResponse preview(Long userId, MultipartFile file) {
        return analyzePreparedInput(prepareInput(userId, file));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String prepareInput(Long userId, MultipartFile file) {
        appUserService.getRequiredUser(userId);
        validateFile(file);
        validateAiReady();

        WorkbookPayload workbookPayload;
        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            workbookPayload = buildWorkbookPayload(userId, defaultFileName(file), workbook);
        } catch (IOException exception) {
            throw new BadRequestException("AI Excel import failed to read the Excel file.");
        }
        try { return objectMapper.writeValueAsString(workbookPayload); }
        catch (JsonProcessingException failure) { throw new BadRequestException("Excel 분석 입력을 저장할 수 없습니다."); }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LedgerExcelPreviewResponse analyzePreparedInput(String preparedInput) {
        return LedgerWorkbookPreviewMapper.response(workbookAnalysis.analyze(preparedInput));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("AI로 분석할 Excel 파일을 업로드해 주세요.");
        }
        if (file.getSize() > MAX_EXCEL_FILE_SIZE_BYTES) {
            throw new BadRequestException("Excel files must not exceed 20 MB.");
        }
        String fileName = defaultFileName(file).toLowerCase(Locale.ROOT);
        if (!(fileName.endsWith(".xlsx") || fileName.endsWith(".xls"))) {
            throw new BadRequestException("AI Excel 가져오기는 .xlsx 또는 .xls 파일만 지원합니다.");
        }
    }

    private void validateAiReady() {
        if (!aiProperties.isFeatureConfigured(LedgerAiFeature.EXCEL_IMPORT)) {
            throw new BadRequestException(aiProperties.featureStatusMessage(LedgerAiFeature.EXCEL_IMPORT));
        }
    }

    private WorkbookPayload buildWorkbookPayload(Long userId, String fileName, Workbook workbook) {
        DataFormatter formatter = new DataFormatter(Locale.KOREA);
        KnownLedgerChoices knownChoices = loadKnownLedgerChoices(userId);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("task", "ledger_excel_import");
        payload.put("fileName", fileName);
        payload.put("instruction", "Convert arbitrary household ledger Excel rows into TravelLedger import rows. Do not insert data.");
        payload.set("targetSchema", buildTargetSchema());
        payload.set("knownPaymentMethods", knownChoices.paymentMethodsPayload());
        payload.set("knownCategories", knownChoices.categoriesPayload());

        ArrayNode sheetsNode = payload.putArray("sheets");
        List<String> sheetNames = new ArrayList<>();
        Map<String, SourceReference> sourceReferencesById = new LinkedHashMap<>();
        Map<String, SourceReference> sourceReferencesByLocation = new LinkedHashMap<>();
        int includedRows = 0;
        int skippedRows = 0;

        for (Sheet sheet : workbook) {
            if (includedRows >= MAX_WORKBOOK_ROWS) {
                break;
            }
            ObjectNode sheetNode = sheetsNode.addObject();
            sheetNode.put("sheetName", sheet.getSheetName());
            ArrayNode rowsNode = sheetNode.putArray("rows");
            int sheetIncludedRows = 0;

            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum() && includedRows < MAX_WORKBOOK_ROWS; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                List<String> cells = readRowCells(row, formatter);
                if (cells.isEmpty()) {
                    skippedRows++;
                    continue;
                }

                ObjectNode rowNode = rowsNode.addObject();
                String sourceId = "sheet-" + workbook.getSheetIndex(sheet) + "-row-" + (rowIndex + 1);
                SourceReference sourceReference = new SourceReference(sourceId, sheet.getSheetName(), rowIndex + 1);
                sourceReferencesById.put(sourceId, sourceReference);
                sourceReferencesByLocation.put(sourceLocationKey(sheet.getSheetName(), rowIndex + 1), sourceReference);
                rowNode.put("sourceId", sourceId);
                rowNode.put("rowNumber", rowIndex + 1);
                ArrayNode cellsNode = rowNode.putArray("cells");
                cells.forEach(cellsNode::add);
                sheetIncludedRows++;
                includedRows++;
            }

            if (sheetIncludedRows > 0) {
                sheetNames.add(sheet.getSheetName());
            } else {
                sheetsNode.remove(sheetsNode.size() - 1);
            }
        }

        if (includedRows == 0) {
            throw new BadRequestException("AI가 분석할 수 있는 Excel 행이 없습니다.");
        }

        return new WorkbookPayload(
                fileName,
                String.join(", ", sheetNames),
                includedRows,
                skippedRows,
                knownChoices,
                Map.copyOf(sourceReferencesById),
                Map.copyOf(sourceReferencesByLocation),
                payload
        );
    }
    private ObjectNode buildTargetSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("sourceId", "required; exact sourceId from the input row; never invent or change it");
        schema.put("entryDate", "YYYY-MM-DD, required");
        schema.put("entryTime", "HH:mm or null");
        schema.put("title", "transaction title, required");
        schema.put("memo", "source notes or conversion reason");
        schema.put("amount", "positive KRW number only");
        schema.put("entryType", "EXPENSE or INCOME");
        schema.put("paymentMethodName", "exact known payment method name, otherwise empty");
        schema.put("categoryGroupName", "exact known category group name, otherwise empty");
        schema.put("categoryDetailName", "exact detail registered under categoryGroupName, otherwise empty");
        schema.put("sourceSheetName", "original sheet name");
        schema.put("sourceRowNumber", "original 1-based row number");
        return schema;
    }

    private KnownLedgerChoices loadKnownLedgerChoices(Long userId) {
        List<String> paymentMethods = paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(userId).stream()
                .map(PaymentMethod::getName)
                .filter(this::hasText)
                .limit(80)
                .toList();
        Map<String, Set<String>> categoryDetailsByGroup = new LinkedHashMap<>();
        ArrayNode categoriesPayload = objectMapper.createArrayNode();
        var groups = categoryGroupRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(userId).stream()
                .filter(group -> hasText(group.getName()))
                .limit(120)
                .toList();
        Map<Long, List<CategoryDetail>> details = groups.isEmpty() ? Map.of()
                : categoryDetailRepository.findAllByGroupIdInOrderByDisplayOrderAscIdAsc(groups.stream().map(group -> group.getId()).toList())
                .stream().collect(java.util.stream.Collectors.groupingBy(detail -> detail.getGroup().getId()));
        groups.forEach(group -> {
                    ObjectNode groupNode = categoriesPayload.addObject();
                    groupNode.put("entryType", group.getEntryType() == null ? "EXPENSE" : group.getEntryType().name());
                    groupNode.put("categoryGroupName", group.getName());
                    ArrayNode detailsNode = groupNode.putArray("details");
                    Set<String> detailNames = details.getOrDefault(group.getId(), List.of()).stream()
                            .map(CategoryDetail::getName)
                            .filter(this::hasText)
                            .limit(40)
                            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                    detailNames.forEach(detailsNode::add);
                    categoryDetailsByGroup.put(group.getName(), Set.copyOf(detailNames));
                });
        ArrayNode paymentMethodsPayload = objectMapper.createArrayNode();
        paymentMethods.forEach(paymentMethodsPayload::add);
        return new KnownLedgerChoices(
                Set.copyOf(paymentMethods),
                Map.copyOf(categoryDetailsByGroup),
                paymentMethodsPayload,
                categoriesPayload
        );
    }
    private List<String> readRowCells(Row row, DataFormatter formatter) {
        if (row == null || row.getLastCellNum() < 0) {
            return List.of();
        }
        List<String> cells = new ArrayList<>();
        boolean hasText = false;
        int lastCell = Math.min(row.getLastCellNum(), MAX_CELLS_PER_ROW);
        for (int cellIndex = 0; cellIndex < lastCell; cellIndex++) {
            String value = readCellText(row.getCell(cellIndex), formatter);
            if (!value.isBlank()) {
                hasText = true;
            }
            cells.add(value);
        }
        while (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) {
            cells.remove(cells.size() - 1);
        }
        return hasText ? cells : List.of();
    }

    private String readCellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalDate().toString();
        }
        return formatter.formatCellValue(cell).replaceAll("\\R+", " ").trim();
    }

    private String sourceLocationKey(String sheetName, int rowNumber) {
        return LedgerWorkbookPreviewPolicy.locationKey(sheetName,rowNumber);
    }
    private String defaultFileName(MultipartFile file) {
        return Optional.ofNullable(file.getOriginalFilename()).filter(this::hasText).orElse("ai-ledger-import.xlsx");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
