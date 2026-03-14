package com.playdata.calen.ledger.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.dto.LedgerExcelImportRequest;
import com.playdata.calen.ledger.dto.LedgerExcelImportResultResponse;
import com.playdata.calen.ledger.dto.LedgerExcelImportRowRequest;
import com.playdata.calen.ledger.dto.LedgerExcelPreviewResponse;
import com.playdata.calen.ledger.dto.LedgerExcelPreviewRowResponse;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerExcelImportService {

    private static final DataFormatter DATA_FORMATTER = new DataFormatter(Locale.KOREA);
    private static final Pattern DIGIT_PATTERN = Pattern.compile("[^0-9.\\-]");
    private static final Pattern MONTH_SHEET_PATTERN = Pattern.compile("^[0-9]{1,2}월$");
    private static final Pattern YEAR_PATTERN = Pattern.compile("(19|20)\\d{2}");
    private static final int MONTH_HEADER_ROW_INDEX = 2;
    private static final int MONTH_NO_COLUMN = 10;
    private static final int MONTH_DATE_COLUMN = 11;
    private static final int MONTH_TITLE_COLUMN = 12;
    private static final int MONTH_AMOUNT_COLUMN = 13;
    private static final int MONTH_PAYMENT_COLUMN = 14;
    private static final int MONTH_CATEGORY_COLUMN = 15;
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
    );
    private static final String DEFAULT_IMPORTED_TITLE = "작성하지 않음";

    private final AppUserService appUserService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final PaymentMethodRepository paymentMethodRepository;

    public LedgerExcelPreviewResponse preview(Long userId, MultipartFile file) {
        appUserService.getRequiredUser(userId);
        validateFile(file);

        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            Optional<LedgerExcelPreviewResponse> monthlyPreview = buildMonthlyPreview(file.getOriginalFilename(), workbook);
            if (monthlyPreview.isPresent()) {
                return monthlyPreview.get();
            }
            SheetHeaderMatch match = findBestHeaderMatch(workbook)
                    .orElseThrow(() -> new BadRequestException("Could not detect a supported transaction table in the Excel file."));
            return buildPreview(file.getOriginalFilename(), match.sheet(), match.header());
        } catch (IOException exception) {
            throw new BadRequestException("Failed to read the Excel file.");
        }
    }

    @Transactional
    public LedgerExcelImportResultResponse importRows(Long userId, LedgerExcelImportRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        Set<String> createdGroupNames = new LinkedHashSet<>();
        Set<String> createdDetailNames = new LinkedHashSet<>();
        Set<String> createdPaymentNames = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        int importedCount = 0;
        int skippedCount = 0;

        for (LedgerExcelImportRowRequest row : request.rows()) {
            if (!row.selected()) {
                skippedCount++;
                continue;
            }

            String resolvedTitle = defaultIfBlank(row.title(), DEFAULT_IMPORTED_TITLE);

            if (ledgerEntryRepository.existsByOwnerIdAndEntryDateAndTitleAndAmount(userId, row.entryDate(), resolvedTitle.trim(), row.amount())) {
                skippedCount++;
                warnings.add(buildWarning(row, "Skipped as a likely duplicate."));
                continue;
            }

            CategoryGroup group = resolveOrCreateCategoryGroup(owner, row, createdGroupNames);
            CategoryDetail detail = resolveOrCreateCategoryDetail(group, row, createdDetailNames);
            PaymentMethod paymentMethod = resolveOrCreatePaymentMethod(owner, row, createdPaymentNames);
            LedgerEntryTextSanitizer.SanitizedLedgerText sanitizedText =
                    LedgerEntryTextSanitizer.sanitize(resolvedTitle, buildImportedMemo(row));

            LedgerEntry entry = new LedgerEntry();
            entry.setOwner(owner);
            entry.setEntryDate(row.entryDate());
            entry.setEntryTime(row.entryTime());
            entry.setTitle(sanitizedText.title());
            entry.setMemo(sanitizedText.memo());
            entry.setAmount(row.amount());
            entry.setEntryType(row.entryType());
            entry.setCategoryGroup(group);
            entry.setCategoryDetail(detail);
            entry.setPaymentMethod(paymentMethod);
            ledgerEntryRepository.save(entry);
            importedCount++;
        }

        return new LedgerExcelImportResultResponse(
                request.rows().size(),
                importedCount,
                skippedCount,
                List.copyOf(createdGroupNames),
                List.copyOf(createdDetailNames),
                List.copyOf(createdPaymentNames),
                warnings
        );
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Please upload an Excel file.");
        }

        String fileName = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        if (!(fileName.endsWith(".xlsx") || fileName.endsWith(".xls"))) {
            throw new BadRequestException("Only .xlsx or .xls files are supported.");
        }
    }

    private Optional<LedgerExcelPreviewResponse> buildMonthlyPreview(String fileName, Workbook workbook) {
        List<LedgerExcelPreviewRowResponse> rows = new ArrayList<>();
        List<String> sheetNames = new ArrayList<>();
        int skippedRowCount = 0;

        for (Sheet sheet : workbook) {
            if (!isMonthlyLedgerSheet(sheet)) {
                continue;
            }

            sheetNames.add(sheet.getSheetName());
            MonthlyPreviewResult result = extractMonthlyRows(sheet, rows.size(), resolveMonthlyFallbackDate(fileName, sheet));
            rows.addAll(result.rows());
            skippedRowCount += result.skippedRowCount();
        }

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        List<String> notes = List.of(
                "Imported monthly sheets that use K:P columns.",
                "Blank dates inherit the latest date in the same month sheet, or fall back to the 1st day of that month.",
                "Wrapped descriptions are merged until an amount row closes the transaction."
        );

        int readyRowCount = (int) rows.stream().filter(LedgerExcelPreviewRowResponse::ready).count();
        return Optional.of(new LedgerExcelPreviewResponse(
                defaultIfBlank(fileName, "ledger-import.xlsx"),
                String.join(", ", sheetNames),
                MONTH_HEADER_ROW_INDEX + 1,
                rows.size(),
                readyRowCount,
                skippedRowCount,
                notes,
                rows
        ));
    }

    private Optional<SheetHeaderMatch> findBestHeaderMatch(Workbook workbook) {
        SheetHeaderMatch bestMatch = null;

        for (Sheet sheet : workbook) {
            HeaderMatch header = detectHeader(sheet);
            if (header == null) {
                continue;
            }
            if (bestMatch == null || header.score() > bestMatch.header().score()) {
                bestMatch = new SheetHeaderMatch(sheet, header);
            }
        }

        return Optional.ofNullable(bestMatch);
    }

    private HeaderMatch detectHeader(Sheet sheet) {
        int lastRow = Math.min(sheet.getLastRowNum(), 200);
        HeaderMatch best = null;

        for (int rowIndex = 0; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }

            Map<ColumnType, Integer> columns = new EnumMap<>(ColumnType.class);
            short lastCellNum = row.getLastCellNum();
            if (lastCellNum < 0) {
                continue;
            }

            for (int cellIndex = 0; cellIndex < lastCellNum; cellIndex++) {
                String normalized = normalizeHeaderValue(DATA_FORMATTER.formatCellValue(row.getCell(cellIndex)));
                ColumnType type = detectColumnType(normalized);
                if (type != null && !columns.containsKey(type)) {
                    columns.put(type, cellIndex);
                }
            }

            int score = columns.size();
            if (columns.containsKey(ColumnType.DATE) && columns.containsKey(ColumnType.TITLE) && columns.containsKey(ColumnType.AMOUNT) && score >= 3) {
                HeaderMatch candidate = new HeaderMatch(rowIndex, columns, score);
                if (best == null || candidate.score() > best.score()) {
                    best = candidate;
                }
            }
        }

        return best;
    }

    private boolean isMonthlyLedgerSheet(Sheet sheet) {
        if (!MONTH_SHEET_PATTERN.matcher(sheet.getSheetName()).matches()) {
            return false;
        }

        Row headerRow = sheet.getRow(MONTH_HEADER_ROW_INDEX);
        if (headerRow == null) {
            return false;
        }

        return "No.".equalsIgnoreCase(readCellText(headerRow, MONTH_NO_COLUMN).trim())
                && normalizeHeaderValue(readCellText(headerRow, MONTH_DATE_COLUMN)).contains(normalizeHeaderValue("거래일"))
                && normalizeHeaderValue(readCellText(headerRow, MONTH_TITLE_COLUMN)).contains(normalizeHeaderValue("지출내용"))
                && normalizeHeaderValue(readCellText(headerRow, MONTH_AMOUNT_COLUMN)).contains(normalizeHeaderValue("지출금액"))
                && normalizeHeaderValue(readCellText(headerRow, MONTH_PAYMENT_COLUMN)).contains(normalizeHeaderValue("지출방법"))
                && normalizeHeaderValue(readCellText(headerRow, MONTH_CATEGORY_COLUMN)).contains(normalizeHeaderValue("소비분류"));
    }

    private MonthlyPreviewResult extractMonthlyRows(Sheet sheet, int previewOffset, LocalDate monthlyFallbackDate) {
        List<LedgerExcelPreviewRowResponse> rows = new ArrayList<>();
        List<String> pendingTitleParts = new ArrayList<>();
        String pendingPaymentMethod = null;
        String pendingCategoryName = null;
        LocalDate currentDate = null;
        LocalDate pendingDate = null;
        int skippedRowCount = 0;
        int blankRun = 0;

        for (int rowIndex = MONTH_HEADER_ROW_INDEX + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isMonthlyRowBlank(row)) {
                blankRun++;
                if (blankRun >= 10) {
                    break;
                }
                continue;
            }

            blankRun = 0;

            LocalDate parsedDate = parseDate(row, MONTH_DATE_COLUMN);
            if (parsedDate != null) {
                currentDate = parsedDate;
                if (pendingDate == null) {
                    pendingDate = parsedDate;
                }
            }

            String titlePart = blankToNull(readCellText(row, MONTH_TITLE_COLUMN));
            BigDecimal amount = parseAmount(row, MONTH_AMOUNT_COLUMN);
            String paymentMethodName = blankToNull(readCellText(row, MONTH_PAYMENT_COLUMN));
            String categoryGroupName = blankToNull(readCellText(row, MONTH_CATEGORY_COLUMN));

            if (looksLikeSectionOrTotalRow(titlePart, amount)) {
                skippedRowCount++;
                continue;
            }

            if (amount == null) {
                if (titlePart != null) {
                    pendingTitleParts.add(titlePart);
                }
                if (paymentMethodName != null) {
                    pendingPaymentMethod = paymentMethodName;
                }
                if (categoryGroupName != null) {
                    pendingCategoryName = categoryGroupName;
                }
                if (pendingDate == null) {
                    pendingDate = currentDate;
                }
                continue;
            }

            String title = defaultIfBlank(mergeTitleParts(pendingTitleParts, titlePart), DEFAULT_IMPORTED_TITLE);
            LocalDate entryDate = parsedDate != null
                    ? parsedDate
                    : pendingDate != null
                            ? pendingDate
                            : currentDate != null
                                    ? currentDate
                                    : monthlyFallbackDate;
            String resolvedPaymentMethod = defaultIfBlank(paymentMethodName, defaultIfBlank(pendingPaymentMethod, "기타"));
            String resolvedCategory = defaultIfBlank(categoryGroupName, defaultIfBlank(pendingCategoryName, "미분류"));

            List<String> issues = new ArrayList<>();
            if (entryDate == null) {
                issues.add("Missing date");
            }
            if (amount.signum() <= 0) {
                issues.add("Amount must be positive");
            }

            boolean ready = issues.isEmpty();
            if (!ready) {
                skippedRowCount++;
            }

            rows.add(new LedgerExcelPreviewRowResponse(
                    previewOffset + rows.size() + 1,
                    sheet.getSheetName(),
                    row.getRowNum() + 1,
                    entryDate,
                    null,
                    title,
                    null,
                    amount,
                    EntryType.EXPENSE,
                    resolvedPaymentMethod,
                    resolvedCategory,
                    null,
                    ready,
                    issues
            ));

            pendingTitleParts.clear();
            pendingPaymentMethod = null;
            pendingCategoryName = null;
            pendingDate = currentDate;
        }

        return new MonthlyPreviewResult(rows, skippedRowCount);
    }

    private LedgerExcelPreviewResponse buildPreview(String fileName, Sheet sheet, HeaderMatch header) {
        List<LedgerExcelPreviewRowResponse> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int blankRun = 0;
        int skippedRowCount = 0;
        boolean started = false;

        for (int rowIndex = header.rowNumber() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isMappedRowBlank(row, header.columns())) {
                if (started) {
                    blankRun++;
                    if (blankRun >= 5) {
                        break;
                    }
                }
                continue;
            }

            started = true;
            blankRun = 0;

            String rawDate = readCellText(row, header.columns().get(ColumnType.DATE));
            String rawTitle = readCellText(row, header.columns().get(ColumnType.TITLE));
            String rawAmount = readCellText(row, header.columns().get(ColumnType.AMOUNT));

            if (isSummaryOrSectionRow(rawDate, rawTitle, rawAmount)) {
                skippedRowCount++;
                continue;
            }

            LocalDate entryDate = parseDate(row, header.columns().get(ColumnType.DATE));
            BigDecimal amount = parseAmount(row, header.columns().get(ColumnType.AMOUNT));
            String title = defaultIfBlank(normalizeText(rawTitle), DEFAULT_IMPORTED_TITLE);
            String paymentMethodName = defaultIfBlank(readCellText(row, header.columns().get(ColumnType.PAYMENT)), "기타");
            String categoryGroupName = defaultIfBlank(readCellText(row, header.columns().get(ColumnType.GROUP)), "미분류");
            String categoryDetailName = blankToNull(readCellText(row, header.columns().get(ColumnType.DETAIL)));

            List<String> issues = new ArrayList<>();
            if (entryDate == null) {
                issues.add("거래일을 읽지 못했습니다.");
            }
            if (amount == null || amount.signum() <= 0) {
                issues.add("지출금액을 읽지 못했습니다.");
            }

            boolean ready = issues.isEmpty();
            if (!ready) {
                skippedRowCount++;
            }

            rows.add(new LedgerExcelPreviewRowResponse(
                    rows.size() + 1,
                    sheet.getSheetName(),
                    row.getRowNum() + 1,
                    entryDate,
                    null,
                    title,
                    null,
                    amount,
                    EntryType.EXPENSE,
                    paymentMethodName,
                    categoryGroupName,
                    categoryDetailName,
                    ready,
                    issues
            ));
        }

        if (rows.isEmpty()) {
            throw new BadRequestException("No transaction rows were detected in the Excel file.");
        }

        notes.add("Only the row-based transaction table is imported. Summary blocks are ignored.");
        notes.add("Missing payment methods and categories are created automatically during import.");

        int readyRowCount = (int) rows.stream().filter(LedgerExcelPreviewRowResponse::ready).count();
        return new LedgerExcelPreviewResponse(
                defaultIfBlank(fileName, "ledger-import.xlsx"),
                sheet.getSheetName(),
                header.rowNumber() + 1,
                rows.size(),
                readyRowCount,
                skippedRowCount,
                notes,
                rows
        );
    }

    private boolean isMappedRowBlank(Row row, Map<ColumnType, Integer> columns) {
        for (Integer columnIndex : columns.values()) {
            if (columnIndex != null && !readCellText(row, columnIndex).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean isMonthlyRowBlank(Row row) {
        return readCellText(row, MONTH_DATE_COLUMN).isBlank()
                && readCellText(row, MONTH_TITLE_COLUMN).isBlank()
                && readCellText(row, MONTH_AMOUNT_COLUMN).isBlank()
                && readCellText(row, MONTH_PAYMENT_COLUMN).isBlank()
                && readCellText(row, MONTH_CATEGORY_COLUMN).isBlank();
    }

    private boolean looksLikeSectionOrTotalRow(String title, BigDecimal amount) {
        String normalizedTitle = normalizeText(title);
        if (normalizedTitle == null) {
            return amount == null;
        }
        String lowered = normalizedTitle.toLowerCase(Locale.ROOT);
        return lowered.contains("합계")
                || lowered.contains("고정")
                || lowered.contains("비정기");
    }

    private String mergeTitleParts(List<String> pendingTitleParts, String titlePart) {
        List<String> chunks = new ArrayList<>();
        pendingTitleParts.stream()
                .map(this::blankToNull)
                .filter(Objects::nonNull)
                .forEach(chunks::add);
        String current = blankToNull(titlePart);
        if (current != null) {
            chunks.add(current);
        }
        if (chunks.isEmpty()) {
            return null;
        }
        return String.join(" ", chunks);
    }

    private boolean isSummaryOrSectionRow(String rawDate, String rawTitle, String rawAmount) {
        String merged = String.join(" ", List.of(
                normalizeText(rawDate) == null ? "" : normalizeText(rawDate),
                normalizeText(rawTitle) == null ? "" : normalizeText(rawTitle),
                normalizeText(rawAmount) == null ? "" : normalizeText(rawAmount)
        )).trim();
        if (merged.isEmpty()) {
            return true;
        }
        return merged.contains("합계")
                || merged.contains("고정 지출")
                || merged.contains("고정 저축")
                || merged.contains("고정 대출")
                || merged.contains("비정기 저축");
    }

    private LocalDate parseDate(Row row, Integer columnIndex) {
        if (row == null || columnIndex == null) {
            return null;
        }
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double numericValue = cell.getNumericCellValue();
            if (DateUtil.isCellDateFormatted(cell) || (numericValue > 20000 && numericValue < 60000)) {
                return DateUtil.getLocalDateTime(numericValue).toLocalDate();
            }
        }

        String value = normalizeText(DATA_FORMATTER.formatCellValue(cell));
        if (value == null) {
            return null;
        }

        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported date pattern.
            }
        }
        return null;
    }

    private BigDecimal parseAmount(Row row, Integer columnIndex) {
        if (columnIndex == null) {
            return null;
        }
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        }

        String value = DIGIT_PATTERN.matcher(DATA_FORMATTER.formatCellValue(cell)).replaceAll("");
        if (value.isBlank() || "-".equals(value) || ".".equals(value)) {
            return null;
        }

        try {
            return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String readCellText(Row row, Integer columnIndex) {
        if (row == null || columnIndex == null) {
            return "";
        }
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return "";
        }
        return DATA_FORMATTER.formatCellValue(cell);
    }

    private String buildImportedMemo(LedgerExcelImportRowRequest row) {
        return blankToNull(row.memo());
    }

    private LocalDate resolveMonthlyFallbackDate(String fileName, Sheet sheet) {
        Integer month = inferMonthFromSheetName(sheet.getSheetName());
        if (month == null) {
            return null;
        }

        Integer year = inferYearFromFileName(fileName);
        if (year == null) {
            year = inferYearFromSheet(sheet);
        }
        if (year == null) {
            year = LocalDate.now().getYear();
        }

        return LocalDate.of(year, month, 1);
    }

    private Integer inferMonthFromSheetName(String sheetName) {
        String normalized = Optional.ofNullable(sheetName).orElse("").trim();
        if (!MONTH_SHEET_PATTERN.matcher(normalized).matches()) {
            return null;
        }
        return Integer.parseInt(normalized.replace("월", ""));
    }

    private Integer inferYearFromFileName(String fileName) {
        java.util.regex.Matcher matcher = YEAR_PATTERN.matcher(Optional.ofNullable(fileName).orElse(""));
        if (!matcher.find()) {
            return null;
        }
        return Integer.parseInt(matcher.group());
    }

    private Integer inferYearFromSheet(Sheet sheet) {
        for (int rowIndex = MONTH_HEADER_ROW_INDEX + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            LocalDate parsedDate = parseDate(sheet.getRow(rowIndex), MONTH_DATE_COLUMN);
            if (parsedDate != null) {
                return parsedDate.getYear();
            }
        }
        return null;
    }

    private CategoryGroup resolveOrCreateCategoryGroup(AppUser owner, LedgerExcelImportRowRequest row, Set<String> createdGroupNames) {
        String name = defaultIfBlank(row.categoryGroupName(), "미분류");
        return categoryGroupRepository.findByOwnerIdAndEntryTypeAndNameIgnoreCase(owner.getId(), row.entryType(), name)
                .orElseGet(() -> {
                    CategoryGroup group = new CategoryGroup();
                    group.setOwner(owner);
                    group.setName(name);
                    group.setEntryType(row.entryType());
                    group.setDisplayOrder(0);
                    group.setActive(true);
                    createdGroupNames.add(name);
                    return categoryGroupRepository.save(group);
                });
    }

    private CategoryDetail resolveOrCreateCategoryDetail(CategoryGroup group, LedgerExcelImportRowRequest row, Set<String> createdDetailNames) {
        String detailName = blankToNull(row.categoryDetailName());
        if (detailName == null) {
            return null;
        }

        return categoryDetailRepository.findByGroupIdAndNameIgnoreCase(group.getId(), detailName)
                .orElseGet(() -> {
                    CategoryDetail detail = new CategoryDetail();
                    detail.setGroup(group);
                    detail.setName(detailName);
                    detail.setDisplayOrder(0);
                    detail.setActive(true);
                    createdDetailNames.add(group.getName() + " / " + detailName);
                    return categoryDetailRepository.save(detail);
                });
    }

    private PaymentMethod resolveOrCreatePaymentMethod(AppUser owner, LedgerExcelImportRowRequest row, Set<String> createdPaymentNames) {
        String name = defaultIfBlank(row.paymentMethodName(), "기타");
        return paymentMethodRepository.findByOwnerIdAndNameIgnoreCase(owner.getId(), name)
                .orElseGet(() -> {
                    PaymentMethod paymentMethod = new PaymentMethod();
                    paymentMethod.setOwner(owner);
                    paymentMethod.setName(name);
                    paymentMethod.setKind(inferPaymentKind(name));
                    paymentMethod.setDisplayOrder(0);
                    paymentMethod.setActive(true);
                    createdPaymentNames.add(name);
                    return paymentMethodRepository.save(paymentMethod);
                });
    }

    private PaymentMethodKind inferPaymentKind(String name) {
        String normalized = normalizeHeaderValue(name);
        if (normalized.contains("card") || normalized.contains("카드")) {
            return PaymentMethodKind.CARD;
        }
        if (normalized.contains("cash") || normalized.contains("현금")) {
            return PaymentMethodKind.CASH;
        }
        if (normalized.contains("point") || normalized.contains("포인트")) {
            return PaymentMethodKind.POINT;
        }
        if (normalized.contains("transfer") || normalized.contains("이체") || normalized.contains("계좌") || normalized.contains("송금")) {
            return PaymentMethodKind.TRANSFER;
        }
        return PaymentMethodKind.OTHER;
    }

    private String buildWarning(LedgerExcelImportRowRequest row, String message) {
        String base = blankToNull(row.sourceSheetName()) != null
                ? row.sourceSheetName() + (row.sourceRowNumber() != null ? " row " + row.sourceRowNumber() : "")
                : row.sourceRowNumber() != null ? "Row " + row.sourceRowNumber() : row.title();
        return base + ": " + message;
    }

    private ColumnType detectColumnType(String normalizedHeader) {
        if (normalizedHeader.isBlank()) {
            return null;
        }
        if (containsAny(normalizedHeader, "거래일", "날짜", "지출일", "입금일")) {
            return ColumnType.DATE;
        }
        if (containsAny(normalizedHeader, "지출내용", "거래내용", "내용", "적요", "항목")) {
            return ColumnType.TITLE;
        }
        if (containsAny(normalizedHeader, "지출금액", "금액", "사용금액", "거래금액", "입출금액")) {
            return ColumnType.AMOUNT;
        }
        if (containsAny(normalizedHeader, "지출방법", "결제수단", "결제방법", "사용수단")) {
            return ColumnType.PAYMENT;
        }
        if (containsAny(normalizedHeader, "소비분류", "카테고리", "대분류", "분류")) {
            return ColumnType.GROUP;
        }
        if (containsAny(normalizedHeader, "비소비분류", "소분류", "세부분류", "비고분류")) {
            return ColumnType.DETAIL;
        }
        return null;
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(normalizeHeaderValue(candidate))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeHeaderValue(String value) {
        return Optional.ofNullable(value)
                .map(item -> item.toLowerCase(Locale.ROOT).replace(" ", "").replace("\n", ""))
                .orElse("");
    }

    private String normalizeText(String value) {
        String normalized = Optional.ofNullable(value).map(String::trim).orElse("");
        return normalized.isEmpty() ? null : normalized;
    }

    private String defaultIfBlank(String value, String fallback) {
        return Objects.requireNonNullElse(blankToNull(value), fallback);
    }

    private String blankToNull(String value) {
        String normalized = normalizeText(value);
        if (normalized == null || "-".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    private enum ColumnType {
        DATE,
        TITLE,
        AMOUNT,
        PAYMENT,
        GROUP,
        DETAIL
    }

    private record HeaderMatch(int rowNumber, Map<ColumnType, Integer> columns, int score) {
    }

    private record SheetHeaderMatch(Sheet sheet, HeaderMatch header) {
    }

    private record MonthlyPreviewResult(List<LedgerExcelPreviewRowResponse> rows, int skippedRowCount) {
    }
}
