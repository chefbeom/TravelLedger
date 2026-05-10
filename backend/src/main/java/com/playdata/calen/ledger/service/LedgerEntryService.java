package com.playdata.calen.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntryChangeAction;
import com.playdata.calen.ledger.domain.LedgerEntryChangeHistory;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.dto.LedgerEntryBulkUpdateRequest;
import com.playdata.calen.ledger.dto.LedgerEntryBulkUpdateResponse;
import com.playdata.calen.ledger.dto.LedgerEntryChangeFieldResponse;
import com.playdata.calen.ledger.dto.LedgerEntryChangeHistoryDetailResponse;
import com.playdata.calen.ledger.dto.LedgerEntryChangeHistoryPageResponse;
import com.playdata.calen.ledger.dto.LedgerEntryChangeHistorySummaryResponse;
import com.playdata.calen.ledger.dto.LedgerEntryChangeItemResponse;
import com.playdata.calen.ledger.dto.LedgerEntryDateRangeResponse;
import com.playdata.calen.ledger.dto.LedgerEntryPageResponse;
import com.playdata.calen.ledger.dto.LedgerEntrySearchPageResponse;
import com.playdata.calen.ledger.dto.LedgerEntrySearchSummaryResponse;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryChangeHistoryRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final LedgerEntryChangeHistoryRepository ledgerEntryChangeHistoryRepository;
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final ObjectMapper objectMapper;

    private static final String INCOME_PAYMENT_METHOD_NAME = "-";
    private static final int MAX_SEARCH_PAGE_SIZE = 100;
    private static final int MAX_TRASH_PAGE_SIZE = 100;
    private static final int MAX_HISTORY_PAGE_SIZE = 50;

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
            Long categoryDetailId,
            boolean paymentMethodOther,
            boolean categoryGroupOther,
            boolean categoryDetailOther,
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
                categoryDetailId,
                paymentMethodOther,
                categoryGroupOther,
                categoryDetailOther,
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
                categoryDetailId,
                paymentMethodOther,
                categoryGroupOther,
                categoryDetailOther,
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
                categoryDetailId,
                paymentMethodOther,
                categoryGroupOther,
                categoryDetailOther,
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

    public LedgerEntryChangeHistoryPageResponse getChangeHistories(Long userId, int page, int size) {
        appUserService.getRequiredUser(userId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_HISTORY_PAGE_SIZE);
        Page<LedgerEntryChangeHistory> resultPage = ledgerEntryChangeHistoryRepository.findAllByOwnerIdOrderByCreatedAtDescIdDesc(
                userId,
                PageRequest.of(safePage, safeSize)
        );

        return new LedgerEntryChangeHistoryPageResponse(
                resultPage.getContent().stream()
                        .map(this::toChangeHistorySummary)
                        .toList(),
                resultPage.getNumber(),
                resultPage.getSize(),
                resultPage.getTotalElements(),
                resultPage.getTotalPages()
        );
    }

    public LedgerEntryChangeHistoryDetailResponse getChangeHistory(Long userId, Long historyId) {
        appUserService.getRequiredUser(userId);
        LedgerEntryChangeHistory history = ledgerEntryChangeHistoryRepository.findByIdAndOwnerId(historyId, userId)
                .orElseThrow(() -> new NotFoundException("변경 이력을 찾을 수 없습니다."));
        return toChangeHistoryDetail(history);
    }

    @Transactional
    public LedgerEntryChangeHistoryDetailResponse restoreChangeHistory(Long userId, Long historyId) {
        AppUser owner = appUserService.getRequiredUser(userId);
        LedgerEntryChangeHistory history = ledgerEntryChangeHistoryRepository.findByIdAndOwnerId(historyId, userId)
                .orElseThrow(() -> new NotFoundException("변경 이력을 찾을 수 없습니다."));
        List<LedgerEntrySnapshot> targetSnapshots = readSnapshots(history.getBeforeSnapshotJson());
        if (targetSnapshots.isEmpty()) {
            throw new BadRequestException("복구할 변경 이력이 비어 있습니다.");
        }

        Set<Long> targetIds = targetSnapshots.stream()
                .map(LedgerEntrySnapshot::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, LedgerEntry> entriesById = ledgerEntryRepository.findAllByOwnerIdAndIdIn(userId, targetIds).stream()
                .collect(Collectors.toMap(LedgerEntry::getId, entry -> entry));
        if (entriesById.size() != targetIds.size()) {
            throw new NotFoundException("복구할 거래 중 찾을 수 없는 거래가 있습니다.");
        }

        List<LedgerEntrySnapshot> beforeRestoreSnapshots = targetSnapshots.stream()
                .map(snapshot -> toSnapshot(entriesById.get(snapshot.id())))
                .toList();
        for (LedgerEntrySnapshot targetSnapshot : targetSnapshots) {
            applySnapshot(userId, entriesById.get(targetSnapshot.id()), targetSnapshot);
        }
        List<LedgerEntrySnapshot> afterRestoreSnapshots = targetSnapshots.stream()
                .map(snapshot -> toSnapshot(entriesById.get(snapshot.id())))
                .toList();

        LedgerEntryChangeHistory restoreHistory = recordChangeHistory(
                owner,
                LedgerEntryChangeAction.RESTORE,
                beforeRestoreSnapshots,
                afterRestoreSnapshots,
                "이력 #" + history.getId() + " 변경 전 상태로 복구"
        );
        return toChangeHistoryDetail(restoreHistory != null ? restoreHistory : history);
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
        LedgerEntrySnapshot beforeSnapshot = toSnapshot(ledgerEntry);
        applyRequest(userId, ledgerEntry, request);
        LedgerEntrySnapshot afterSnapshot = toSnapshot(ledgerEntry);
        recordChangeHistory(
                ledgerEntry.getOwner(),
                LedgerEntryChangeAction.UPDATE,
                List.of(beforeSnapshot),
                List.of(afterSnapshot),
                buildUpdateSummary(beforeSnapshot, afterSnapshot)
        );
        return toResponse(ledgerEntry);
    }

    @Transactional
    public LedgerEntryBulkUpdateResponse bulkUpdate(Long userId, LedgerEntryBulkUpdateRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        Set<Long> targetIds = new LinkedHashSet<>(request.entryIds());
        if (targetIds.isEmpty()) {
            throw new BadRequestException("변경할 거래를 선택해 주세요.");
        }
        if (request.categoryGroupId() == null && request.paymentMethodId() == null) {
            throw new BadRequestException("변경할 대분류나 결제수단을 선택해 주세요.");
        }
        if (request.categoryGroupId() == null && request.categoryDetailId() != null) {
            throw new BadRequestException("소분류를 변경하려면 대분류도 함께 선택해 주세요.");
        }

        List<LedgerEntry> entries = ledgerEntryRepository.findAllByOwnerIdAndDeletedAtIsNullAndIdIn(userId, targetIds);
        if (entries.size() != targetIds.size()) {
            throw new NotFoundException("선택한 거래 중 변경할 수 없는 거래가 있습니다.");
        }

        List<LedgerEntrySnapshot> beforeSnapshots = entries.stream()
                .map(this::toSnapshot)
                .toList();

        CategoryGroup targetGroup = null;
        CategoryDetail targetDetail = null;
        if (request.categoryGroupId() != null) {
            targetGroup = categoryGroupRepository.findByIdAndOwnerId(request.categoryGroupId(), userId)
                    .orElseThrow(() -> new NotFoundException("대분류를 찾을 수 없습니다."));

            if (request.categoryDetailId() != null) {
                targetDetail = categoryDetailRepository.findByIdAndGroupOwnerId(request.categoryDetailId(), userId)
                        .orElseThrow(() -> new NotFoundException("소분류를 찾을 수 없습니다."));
                if (!targetDetail.getGroup().getId().equals(targetGroup.getId())) {
                    throw new BadRequestException("소분류가 선택한 대분류에 속하지 않습니다.");
                }
            }
        }

        PaymentMethod targetPaymentMethod = null;
        if (request.paymentMethodId() != null) {
            targetPaymentMethod = paymentMethodRepository.findByIdAndOwnerId(request.paymentMethodId(), userId)
                    .orElseThrow(() -> new NotFoundException("결제수단을 찾을 수 없습니다."));
        }

        for (LedgerEntry entry : entries) {
            EntryType finalEntryType = targetGroup != null ? targetGroup.getEntryType() : entry.getEntryType();
            entry.setEntryType(finalEntryType);

            if (targetGroup != null) {
                entry.setCategoryGroup(targetGroup);
                entry.setCategoryDetail(targetDetail);
            }

            if (finalEntryType == EntryType.INCOME) {
                entry.setPaymentMethod(resolvePaymentMethod(userId, EntryType.INCOME, null));
            } else if (targetPaymentMethod != null) {
                entry.setPaymentMethod(targetPaymentMethod);
            }
        }

        List<LedgerEntrySnapshot> afterSnapshots = entries.stream()
                .map(this::toSnapshot)
                .toList();
        recordChangeHistory(
                owner,
                LedgerEntryChangeAction.BULK_UPDATE,
                beforeSnapshots,
                afterSnapshots,
                entries.size() + "건 일괄 수정"
        );

        return new LedgerEntryBulkUpdateResponse(entries.size());
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

    private LedgerEntryChangeHistory recordChangeHistory(
            AppUser owner,
            LedgerEntryChangeAction action,
            List<LedgerEntrySnapshot> beforeSnapshots,
            List<LedgerEntrySnapshot> afterSnapshots,
            String summary
    ) {
        Map<Long, LedgerEntrySnapshot> afterById = afterSnapshots.stream()
                .collect(Collectors.toMap(LedgerEntrySnapshot::id, snapshot -> snapshot, (left, right) -> right, LinkedHashMap::new));
        List<LedgerEntrySnapshot> changedBeforeSnapshots = new ArrayList<>();
        List<LedgerEntrySnapshot> changedAfterSnapshots = new ArrayList<>();

        for (LedgerEntrySnapshot beforeSnapshot : beforeSnapshots) {
            LedgerEntrySnapshot afterSnapshot = afterById.get(beforeSnapshot.id());
            if (afterSnapshot != null && hasSnapshotChanges(beforeSnapshot, afterSnapshot)) {
                changedBeforeSnapshots.add(beforeSnapshot);
                changedAfterSnapshots.add(afterSnapshot);
            }
        }

        if (changedBeforeSnapshots.isEmpty()) {
            return null;
        }

        LedgerEntryChangeHistory history = new LedgerEntryChangeHistory();
        history.setOwner(owner);
        history.setAction(action);
        history.setCreatedAt(LocalDateTime.now());
        history.setEntryCount(changedBeforeSnapshots.size());
        history.setSummary(summary);
        history.setBeforeSnapshotJson(writeSnapshots(changedBeforeSnapshots));
        history.setAfterSnapshotJson(writeSnapshots(changedAfterSnapshots));
        return ledgerEntryChangeHistoryRepository.save(history);
    }

    private LedgerEntryChangeHistorySummaryResponse toChangeHistorySummary(LedgerEntryChangeHistory history) {
        return new LedgerEntryChangeHistorySummaryResponse(
                history.getId(),
                history.getCreatedAt(),
                history.getAction().name(),
                toActionLabel(history.getAction()),
                history.getEntryCount(),
                history.getSummary()
        );
    }

    private LedgerEntryChangeHistoryDetailResponse toChangeHistoryDetail(LedgerEntryChangeHistory history) {
        List<LedgerEntrySnapshot> beforeSnapshots = readSnapshots(history.getBeforeSnapshotJson());
        Map<Long, LedgerEntrySnapshot> afterById = readSnapshots(history.getAfterSnapshotJson()).stream()
                .collect(Collectors.toMap(LedgerEntrySnapshot::id, snapshot -> snapshot, (left, right) -> right, LinkedHashMap::new));
        List<LedgerEntryChangeItemResponse> changes = beforeSnapshots.stream()
                .map(beforeSnapshot -> {
                    LedgerEntrySnapshot afterSnapshot = afterById.get(beforeSnapshot.id());
                    return afterSnapshot == null
                            ? null
                            : new LedgerEntryChangeItemResponse(
                                    beforeSnapshot.id(),
                                    beforeSnapshot.title(),
                                    afterSnapshot.title(),
                                    describeSnapshotChanges(beforeSnapshot, afterSnapshot)
                            );
                })
                .filter(Objects::nonNull)
                .toList();

        return new LedgerEntryChangeHistoryDetailResponse(
                history.getId(),
                history.getCreatedAt(),
                history.getAction().name(),
                toActionLabel(history.getAction()),
                history.getEntryCount(),
                history.getSummary(),
                changes
        );
    }

    private LedgerEntrySnapshot toSnapshot(LedgerEntry entry) {
        return new LedgerEntrySnapshot(
                entry.getId(),
                entry.getEntryDate(),
                entry.getEntryTime(),
                entry.getTitle(),
                entry.getMemo(),
                entry.getAmount(),
                entry.getEntryType(),
                entry.getCategoryGroup().getId(),
                entry.getCategoryGroup().getName(),
                entry.getCategoryDetail() != null ? entry.getCategoryDetail().getId() : null,
                entry.getCategoryDetail() != null ? entry.getCategoryDetail().getName() : null,
                entry.getPaymentMethod().getId(),
                entry.getPaymentMethod().getName(),
                entry.getDeletedAt()
        );
    }

    private void applySnapshot(Long userId, LedgerEntry entry, LedgerEntrySnapshot snapshot) {
        CategoryGroup group = categoryGroupRepository.findByIdAndOwnerId(snapshot.categoryGroupId(), userId)
                .orElseThrow(() -> new NotFoundException("복구할 대분류를 찾을 수 없습니다."));
        CategoryDetail detail = null;
        if (snapshot.categoryDetailId() != null) {
            detail = categoryDetailRepository.findByIdAndGroupOwnerId(snapshot.categoryDetailId(), userId)
                    .orElseThrow(() -> new NotFoundException("복구할 소분류를 찾을 수 없습니다."));
            if (!detail.getGroup().getId().equals(group.getId())) {
                throw new BadRequestException("복구할 소분류가 대분류와 일치하지 않습니다.");
            }
        }
        PaymentMethod paymentMethod = paymentMethodRepository.findByIdAndOwnerId(snapshot.paymentMethodId(), userId)
                .orElseThrow(() -> new NotFoundException("복구할 결제수단을 찾을 수 없습니다."));

        entry.setEntryDate(snapshot.entryDate());
        entry.setEntryTime(snapshot.entryTime());
        entry.setTitle(snapshot.title());
        entry.setMemo(snapshot.memo());
        entry.setAmount(snapshot.amount());
        entry.setEntryType(snapshot.entryType());
        entry.setCategoryGroup(group);
        entry.setCategoryDetail(detail);
        entry.setPaymentMethod(paymentMethod);
        entry.setDeletedAt(snapshot.deletedAt());
    }

    private List<LedgerEntryChangeFieldResponse> describeSnapshotChanges(LedgerEntrySnapshot beforeSnapshot, LedgerEntrySnapshot afterSnapshot) {
        List<LedgerEntryChangeFieldResponse> fields = new ArrayList<>();
        addChange(fields, "날짜", beforeSnapshot.entryDate(), afterSnapshot.entryDate());
        addChange(fields, "시간", beforeSnapshot.entryTime(), afterSnapshot.entryTime());
        addChange(fields, "제목", beforeSnapshot.title(), afterSnapshot.title());
        addChange(fields, "메모", beforeSnapshot.memo(), afterSnapshot.memo());
        addChange(fields, "금액", formatAmount(beforeSnapshot.amount()), formatAmount(afterSnapshot.amount()));
        addChange(fields, "구분", toEntryTypeLabel(beforeSnapshot.entryType()), toEntryTypeLabel(afterSnapshot.entryType()));
        addChange(fields, "대분류", beforeSnapshot.categoryGroupName(), afterSnapshot.categoryGroupName());
        addChange(fields, "소분류", beforeSnapshot.categoryDetailName(), afterSnapshot.categoryDetailName());
        addChange(fields, "결제수단", beforeSnapshot.paymentMethodName(), afterSnapshot.paymentMethodName());
        return fields;
    }

    private boolean hasSnapshotChanges(LedgerEntrySnapshot beforeSnapshot, LedgerEntrySnapshot afterSnapshot) {
        return !describeSnapshotChanges(beforeSnapshot, afterSnapshot).isEmpty();
    }

    private void addChange(List<LedgerEntryChangeFieldResponse> fields, String field, Object beforeValue, Object afterValue) {
        if (Objects.equals(beforeValue, afterValue)) {
            return;
        }
        fields.add(new LedgerEntryChangeFieldResponse(field, formatNullableValue(beforeValue), formatNullableValue(afterValue)));
    }

    private String buildUpdateSummary(LedgerEntrySnapshot beforeSnapshot, LedgerEntrySnapshot afterSnapshot) {
        List<String> changedFields = describeSnapshotChanges(beforeSnapshot, afterSnapshot).stream()
                .map(LedgerEntryChangeFieldResponse::field)
                .limit(4)
                .toList();
        String targetTitle = afterSnapshot.title() != null ? afterSnapshot.title() : beforeSnapshot.title();
        return "'" + truncate(targetTitle, 28) + "' 수정"
                + (changedFields.isEmpty() ? "" : ": " + String.join(", ", changedFields));
    }

    private String writeSnapshots(List<LedgerEntrySnapshot> snapshots) {
        try {
            return objectMapper.writeValueAsString(snapshots);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("거래 변경 이력을 저장할 수 없습니다.", exception);
        }
    }

    private List<LedgerEntrySnapshot> readSnapshots(String snapshotJson) {
        try {
            return objectMapper.readValue(snapshotJson, new TypeReference<List<LedgerEntrySnapshot>>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("거래 변경 이력을 읽을 수 없습니다.", exception);
        }
    }

    private String toActionLabel(LedgerEntryChangeAction action) {
        return switch (action) {
            case UPDATE -> "단건 수정";
            case BULK_UPDATE -> "일괄 수정";
            case RESTORE -> "복구";
        };
    }

    private String toEntryTypeLabel(EntryType entryType) {
        if (entryType == null) {
            return "-";
        }
        return entryType == EntryType.INCOME ? "수입" : "지출";
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    private String formatNullableValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return "-";
        }
        return String.valueOf(value);
    }

    private String truncate(String value, int maxLength) {
        String text = value == null ? "" : value;
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
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
        if (!group.getEntryType().equals(request.entryType())) {
            throw new BadRequestException("대분류의 수입/지출 구분이 거래 타입과 일치하지 않습니다.");
        }

        PaymentMethod paymentMethod = resolvePaymentMethod(userId, request.entryType(), request.paymentMethodId());

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

        if (paymentMethodId == null) {
            throw new BadRequestException("결제수단을 선택해 주세요.");
        }

        return paymentMethodRepository.findByIdAndOwnerId(paymentMethodId, userId)
                .orElseThrow(() -> new NotFoundException("결제수단을 찾을 수 없습니다."));
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

    private record LedgerEntrySnapshot(
            Long id,
            LocalDate entryDate,
            java.time.LocalTime entryTime,
            String title,
            String memo,
            BigDecimal amount,
            EntryType entryType,
            Long categoryGroupId,
            String categoryGroupName,
            Long categoryDetailId,
            String categoryDetailName,
            Long paymentMethodId,
            String paymentMethodName,
            LocalDateTime deletedAt
    ) {
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
