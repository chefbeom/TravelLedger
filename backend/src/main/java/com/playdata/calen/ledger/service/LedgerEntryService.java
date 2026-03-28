package com.playdata.calen.ledger.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

    public List<LedgerEntryResponse> getEntries(Long userId, LocalDate from, LocalDate to) {
        appUserService.getRequiredUser(userId);
        DateRange range = normalizeRange(from, to);
        return ledgerEntryRepository.findAllByOwnerIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(userId, range.from(), range.to()).stream()
                .map(this::toResponse)
                .toList();
    }

    public LedgerCsvExport exportEntriesCsv(Long userId, LocalDate from, LocalDate to) {
        appUserService.getRequiredUser(userId);
        if (from == null && to == null) {
            List<LedgerEntryResponse> entries = ledgerEntryRepository.findAllByOwnerIdOrderByEntryDateAscIdAsc(userId).stream()
                    .map(this::toResponse)
                    .toList();
            return new LedgerCsvExport(
                    LedgerCsvFormatter.buildAllFileName(),
                    LedgerCsvFormatter.format(entries),
                    StandardCharsets.UTF_8.name()
            );
        }

        DateRange range = normalizeRange(from, to);
        List<LedgerEntryResponse> entries = ledgerEntryRepository.findAllByOwnerIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(userId, range.from(), range.to()).stream()
                .map(this::toResponse)
                .toList();
        return new LedgerCsvExport(
                LedgerCsvFormatter.buildFileName(range.from(), range.to()),
                LedgerCsvFormatter.format(entries),
                StandardCharsets.UTF_8.name()
        );
    }

    public List<LedgerEntryResponse> getRecentEntries(Long userId) {
        appUserService.getRequiredUser(userId);
        return ledgerEntryRepository.findTop8ByOwnerIdOrderByEntryDateDescIdDesc(userId).stream()
                .map(this::toResponse)
                .toList();
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
        LedgerEntry ledgerEntry = ledgerEntryRepository.findByIdAndOwnerId(entryId, userId)
                .orElseThrow(() -> new NotFoundException("거래를 찾을 수 없습니다."));
        applyRequest(userId, ledgerEntry, request);
        return toResponse(ledgerEntry);
    }

    @Transactional
    public void delete(Long userId, Long entryId) {
        LedgerEntry ledgerEntry = ledgerEntryRepository.findByIdAndOwnerId(entryId, userId)
                .orElseThrow(() -> new NotFoundException("거래를 찾을 수 없습니다."));
        ledgerEntryRepository.delete(ledgerEntry);
    }

    public List<LedgerEntry> loadRawEntries(Long userId, LocalDate from, LocalDate to) {
        appUserService.getRequiredUser(userId);
        DateRange range = normalizeRange(from, to);
        return ledgerEntryRepository.findAllByOwnerIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(userId, range.from(), range.to());
    }

    private LedgerEntryResponse toResponse(LedgerEntry entry) {
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
                entry.getPaymentMethod().getName(),
                entry.getPaymentMethod().getKind()
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

    private record DateRange(LocalDate from, LocalDate to) {
    }

    public record LedgerCsvExport(
            String fileName,
            byte[] content,
            String charset
    ) {
    }
}
