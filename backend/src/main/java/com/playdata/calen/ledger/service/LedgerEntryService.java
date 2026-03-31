package com.playdata.calen.ledger.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.dto.LedgerEntryDateRangeResponse;
import com.playdata.calen.ledger.dto.LedgerEntryPageResponse;
import com.playdata.calen.ledger.dto.LedgerEntrySearchPageResponse;
import com.playdata.calen.ledger.dto.LedgerEntrySearchSummaryResponse;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerEntryService {

    private final AppUserService appUserService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final PaymentMethodRepository paymentMethodRepository;

    private static final String INCOME_PAYMENT_METHOD_NAME = "-";
    private static final int MAX_SEARCH_PAGE_SIZE = 100;
    private static final int MAX_TRASH_PAGE_SIZE = 100;

    public List<LedgerEntryResponse> getEntries(Long userId, LocalDate from, LocalDate to) {
        appUserService.getRequiredUser(userId);
        DateRange range = normalizeRange(from, to);
        return ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(userId, range.from(), range.to()).stream()
                .map(this::toResponse)
                .toList();
    }

    public LedgerEntryPageResponse getDeletedEntries(Long userId, int page, int size) {
        appUserService.getRequiredUser(userId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_TRASH_PAGE_SIZE);

        Page<LedgerEntry> resultPage = ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDescEntryDateDescIdDesc(
                userId,
                PageRequest.of(safePage, safeSize)
        );

        return new LedgerEntryPageResponse(
                resultPage.getContent().stream()
                        .map(this::toResponse)
                        .toList(),
                resultPage.getNumber(),
                resultPage.getSize(),
                resultPage.getTotalElements(),
                resultPage.getTotalPages()
        );
    }

    public LedgerEntrySearchPageResponse searchEntries(
            Long userId,
            LocalDate from,
            LocalDate to,
            String keyword,
            EntryType entryType,
            Long paymentMethodId,
            Long categoryGroupId,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            String sortBy,
            int page,
            int size
    ) {
        appUserService.getRequiredUser(userId);
        DateRange range = normalizeRange(from, to);
        String normalizedKeyword = normalizeKeyword(keyword);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_SEARCH_PAGE_SIZE);

        Page<LedgerEntry> resultPage = ledgerEntryRepository.searchPageByOwnerIdAndFilters(
                userId,
                range.from(),
                range.to(),
                normalizedKeyword,
                entryType,
                paymentMethodId,
                categoryGroupId,
                minAmount,
                maxAmount,
                PageRequest.of(safePage, safeSize, resolveSearchSort(sortBy))
        );

        BigDecimal income = ledgerEntryRepository.sumAmountByOwnerIdAndFilters(
                userId,
                range.from(),
                range.to(),
                normalizedKeyword,
                entryType,
                paymentMethodId,
                categoryGroupId,
                minAmount,
                maxAmount,
                EntryType.INCOME
        );
        BigDecimal expense = ledgerEntryRepository.sumAmountByOwnerIdAndFilters(
                userId,
                range.from(),
                range.to(),
                normalizedKeyword,
                entryType,
                paymentMethodId,
                categoryGroupId,
                minAmount,
                maxAmount,
                EntryType.EXPENSE
        );
        LedgerEntrySearchSummaryResponse summary = new LedgerEntrySearchSummaryResponse(
                income == null ? BigDecimal.ZERO : income,
                expense == null ? BigDecimal.ZERO : expense,
                (income == null ? BigDecimal.ZERO : income).subtract(expense == null ? BigDecimal.ZERO : expense),
                resultPage.getTotalElements()
        );

        return new LedgerEntrySearchPageResponse(
                resultPage.getContent().stream()
                        .map(this::toResponse)
                        .toList(),
                resultPage.getNumber(),
                resultPage.getSize(),
                resultPage.getTotalElements(),
                resultPage.getTotalPages(),
                summary
        );
    }

    public LedgerCsvExport exportEntriesCsv(Long userId, LocalDate from, LocalDate to) {
        appUserService.getRequiredUser(userId);
        if (from == null && to == null) {
            List<LedgerEntryResponse> entries = ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(userId).stream()
                    .map(this::toResponse)
                    .toList();
            return new LedgerCsvExport(
                    LedgerCsvFormatter.buildAllFileName(),
                    LedgerCsvFormatter.format(entries),
                    StandardCharsets.UTF_8.name()
            );
        }

        DateRange range = normalizeRange(from, to);
        List<LedgerEntryResponse> entries = ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(userId, range.from(), range.to()).stream()
                .map(this::toResponse)
                .toList();
        return new LedgerCsvExport(
                LedgerCsvFormatter.buildFileName(range.from(), range.to()),
                LedgerCsvFormatter.format(entries),
                StandardCharsets.UTF_8.name()
        );
    }

    public LedgerProtectedExport exportEntriesCsvProtected(Long userId, LocalDate from, LocalDate to, String secondaryPin) {
        AppUser user = appUserService.getRequiredUser(userId);
        appUserService.ensureSecondaryPinMatches(user, secondaryPin);

        LedgerCsvExport export = exportEntriesCsv(userId, from, to);
        return new LedgerProtectedExport(
                export.fileName() + ".zip",
                createPasswordProtectedZip(export.fileName(), export.content(), secondaryPin),
                "application/zip"
        );
    }

    public List<LedgerEntryResponse> getRecentEntries(Long userId) {
        appUserService.getRequiredUser(userId);
        return ledgerEntryRepository.findTop8ByOwnerIdAndDeletedAtIsNullOrderByEntryDateDescIdDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    public LedgerEntryDateRangeResponse getEntryDateRange(Long userId) {
        appUserService.getRequiredUser(userId);
        LocalDate earliestDate = ledgerEntryRepository.findTop1ByOwnerIdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(userId)
                .map(LedgerEntry::getEntryDate)
                .orElse(null);
        LocalDate latestDate = ledgerEntryRepository.findTop1ByOwnerIdAndDeletedAtIsNullOrderByEntryDateDescIdDesc(userId)
                .map(LedgerEntry::getEntryDate)
                .orElse(null);
        return new LedgerEntryDateRangeResponse(earliestDate, latestDate);
    }

    @Transactional
    public LedgerEntryResponse create(Long userId, LedgerEntryRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        LedgerEntry ledgerEntry = new LedgerEntry();
        ledgerEntry.setOwner(owner);
        applyRequest(userId, ledgerEntry, request);
        return toResponse(ledgerEntryRepository.save(ledgerEntry));
    }

    @Transactional
    public LedgerEntryResponse update(Long userId, Long entryId, LedgerEntryRequest request) {
        LedgerEntry ledgerEntry = ledgerEntryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(entryId, userId)
                .orElseThrow(() -> new NotFoundException("거래를 찾을 수 없습니다."));
        applyRequest(userId, ledgerEntry, request);
        return toResponse(ledgerEntry);
    }

    @Transactional
    public void delete(Long userId, Long entryId, boolean permanent) {
        LedgerEntry ledgerEntry = ledgerEntryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(entryId, userId)
                .orElseThrow(() -> new NotFoundException("거래를 찾을 수 없습니다."));
        if (permanent) {
            ledgerEntryRepository.delete(ledgerEntry);
            return;
        }
        ledgerEntry.setDeletedAt(LocalDateTime.now());
    }

    @Transactional
    public LedgerEntryResponse restore(Long userId, Long entryId) {
        LedgerEntry ledgerEntry = ledgerEntryRepository.findByIdAndOwnerIdAndDeletedAtIsNotNull(entryId, userId)
                .orElseThrow(() -> new NotFoundException("복구할 내역을 찾을 수 없습니다."));
        ledgerEntry.setDeletedAt(null);
        return toResponse(ledgerEntry);
    }

    @Transactional
    public int emptyTrash(Long userId) {
        appUserService.getRequiredUser(userId);
        return ledgerEntryRepository.deleteAllDeletedByOwnerId(userId);
    }

    public List<LedgerEntry> loadRawEntries(Long userId, LocalDate from, LocalDate to) {
        appUserService.getRequiredUser(userId);
        DateRange range = normalizeRange(from, to);
        return ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(userId, range.from(), range.to());
    }

    private LedgerEntryResponse toResponse(LedgerEntry entry) {
        String paymentMethodName = entry.getEntryType() == EntryType.INCOME
                ? INCOME_PAYMENT_METHOD_NAME
                : entry.getPaymentMethod().getName();
        PaymentMethodKind paymentMethodKind = entry.getEntryType() == EntryType.INCOME
                ? PaymentMethodKind.OTHER
                : entry.getPaymentMethod().getKind();
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getEntryDate(),
                entry.getEntryTime(),
                entry.getTitle(),
                LedgerEntryTextSanitizer.stripImportedMemo(entry.getMemo()),
                entry.getAmount(),
                entry.getEntryType(),
                entry.getCategoryGroup().getId(),
                entry.getCategoryGroup().getName(),
                entry.getCategoryDetail() != null ? entry.getCategoryDetail().getId() : null,
                entry.getCategoryDetail() != null ? entry.getCategoryDetail().getName() : null,
                entry.getPaymentMethod().getId(),
                paymentMethodName,
                paymentMethodKind
        );
    }

    private void applyRequest(Long userId, LedgerEntry ledgerEntry, LedgerEntryRequest request) {
        CategoryGroup group = categoryGroupRepository.findByIdAndOwnerId(request.categoryGroupId(), userId)
                .orElseThrow(() -> new NotFoundException("대분류를 찾을 수 없습니다."));
        PaymentMethod paymentMethod = paymentMethodRepository.findByIdAndOwnerId(request.paymentMethodId(), userId)
                .orElseThrow(() -> new NotFoundException("결제수단을 찾을 수 없습니다."));

        if (!group.getEntryType().equals(request.entryType())) {
            throw new BadRequestException("대분류의 수입/지출 구분이 거래 타입과 일치하지 않습니다.");
        }

        paymentMethod = resolvePaymentMethod(userId, request.entryType(), request.paymentMethodId());

        CategoryDetail detail = null;
        if (request.categoryDetailId() != null) {
            detail = categoryDetailRepository.findByIdAndGroupOwnerId(request.categoryDetailId(), userId)
                    .orElseThrow(() -> new NotFoundException("소분류를 찾을 수 없습니다."));
            if (!detail.getGroup().getId().equals(group.getId())) {
                throw new BadRequestException("소분류가 선택한 대분류에 속하지 않습니다.");
            }
        }

        LedgerEntryTextSanitizer.SanitizedLedgerText sanitizedText = LedgerEntryTextSanitizer.sanitize(request.title(), request.memo());

        ledgerEntry.setEntryDate(request.entryDate());
        ledgerEntry.setEntryTime(request.entryTime());
        ledgerEntry.setTitle(sanitizedText.title());
        ledgerEntry.setMemo(sanitizedText.memo());
        ledgerEntry.setAmount(request.amount());
        ledgerEntry.setEntryType(request.entryType());
        ledgerEntry.setCategoryGroup(group);
        ledgerEntry.setCategoryDetail(detail);
        ledgerEntry.setPaymentMethod(paymentMethod);
    }

    private PaymentMethod resolvePaymentMethod(Long userId, EntryType entryType, Long paymentMethodId) {
        if (entryType == EntryType.INCOME) {
            return paymentMethodRepository.findByOwnerIdAndNameIgnoreCase(userId, INCOME_PAYMENT_METHOD_NAME)
                    .orElseGet(() -> createIncomePaymentMethod(userId));
        }

        return paymentMethodRepository.findByIdAndOwnerId(paymentMethodId, userId)
                .orElseThrow(() -> new NotFoundException("寃곗젣?섎떒??李얠쓣 ???놁뒿?덈떎."));
    }

    private PaymentMethod createIncomePaymentMethod(Long userId) {
        AppUser owner = appUserService.getRequiredUser(userId);
        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setOwner(owner);
        paymentMethod.setName(INCOME_PAYMENT_METHOD_NAME);
        paymentMethod.setKind(PaymentMethodKind.OTHER);
        paymentMethod.setDisplayOrder(Integer.MAX_VALUE);
        paymentMethod.setActive(false);
        return paymentMethodRepository.save(paymentMethod);
    }

    private DateRange normalizeRange(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            YearMonth currentMonth = YearMonth.now();
            return new DateRange(currentMonth.atDay(1), currentMonth.atEndOfMonth());
        }
        if (from == null || to == null) {
            throw new BadRequestException("from, to 날짜를 함께 전달해 주세요.");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("from 날짜는 to 날짜보다 앞서야 합니다.");
        }
        return new DateRange(from, to);
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmedKeyword = keyword.trim().toLowerCase();
        return trimmedKeyword.isBlank() ? null : trimmedKeyword;
    }

    private Sort resolveSearchSort(String sortBy) {
        return switch (sortBy) {
            case "AMOUNT_DESC" -> Sort.by(
                    Sort.Order.desc("amount"),
                    Sort.Order.desc("entryDate"),
                    Sort.Order.desc("id")
            );
            case "AMOUNT_ASC" -> Sort.by(
                    Sort.Order.asc("amount"),
                    Sort.Order.desc("entryDate"),
                    Sort.Order.desc("id")
            );
            case "DATE_ASC" -> Sort.by(
                    Sort.Order.asc("entryDate"),
                    Sort.Order.asc("entryTime"),
                    Sort.Order.asc("id")
            );
            case "DATE_DESC" -> Sort.by(
                    Sort.Order.desc("entryDate"),
                    Sort.Order.desc("entryTime"),
                    Sort.Order.desc("id")
            );
            default -> Sort.by(
                    Sort.Order.desc("entryDate"),
                    Sort.Order.desc("entryTime"),
                    Sort.Order.desc("id")
            );
        };
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }

    public record LedgerCsvExport(
            String fileName,
            byte[] content,
            String charset
    ) {
    }

    public record LedgerProtectedExport(
            String fileName,
            byte[] content,
            String contentType
    ) {
    }

    private byte[] createPasswordProtectedZip(String fileName, byte[] content, String password) {
        Path tempZipPath = null;
        try {
            tempZipPath = Files.createTempFile("ledger-export-", ".zip");
            ZipParameters zipParameters = new ZipParameters();
            zipParameters.setFileNameInZip(fileName);
            zipParameters.setEncryptFiles(true);
            zipParameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
            zipParameters.setCompressionMethod(CompressionMethod.DEFLATE);
            zipParameters.setCompressionLevel(CompressionLevel.NORMAL);
            try (ZipFile zipFile = new ZipFile(tempZipPath.toFile(), password.toCharArray());
                 ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
                zipFile.addStream(inputStream, zipParameters);
            }
            return Files.readAllBytes(tempZipPath);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("CSV 압축 파일을 생성하지 못했습니다.", exception);
        } finally {
            if (tempZipPath != null) {
                try {
                    Files.deleteIfExists(tempZipPath);
                } catch (java.io.IOException ignored) {
                    // Ignore cleanup errors for temporary files.
                }
            }
        }
    }
}
