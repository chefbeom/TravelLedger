package com.playdata.calen.ledger.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.RecurringLedgerMode;
import com.playdata.calen.ledger.domain.RecurringLedgerOccurrence;
import com.playdata.calen.ledger.domain.RecurringLedgerOccurrenceStatus;
import com.playdata.calen.ledger.domain.RecurringLedgerRule;
import com.playdata.calen.ledger.domain.RecurringLedgerScheduleType;
import com.playdata.calen.ledger.dto.LedgerEntryRequest;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.ledger.dto.RecurringLedgerOccurrenceResponse;
import com.playdata.calen.ledger.dto.RecurringLedgerRuleRequest;
import com.playdata.calen.ledger.dto.RecurringLedgerRuleResponse;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import com.playdata.calen.ledger.repository.RecurringLedgerOccurrenceRepository;
import com.playdata.calen.ledger.repository.RecurringLedgerRuleRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecurringLedgerService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private final AppUserService appUserService;
    private final RecurringLedgerRuleRepository ruleRepository;
    private final RecurringLedgerOccurrenceRepository occurrenceRepository;
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final LedgerEntryService ledgerEntryService;

    public List<RecurringLedgerRuleResponse> listRules(Long userId) {
        appUserService.getRequiredUser(userId);
        LocalDate today = today();
        return ruleRepository.findAllByOwnerIdOrderByActiveDescDayOfMonthAscIdAsc(userId).stream()
                .map(rule -> toRuleResponse(rule, today))
                .toList();
    }

    @Transactional
    public RecurringLedgerRuleResponse createRule(Long userId, RecurringLedgerRuleRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        RecurringLedgerRule rule = new RecurringLedgerRule();
        rule.setOwner(owner);
        applyRequest(userId, rule, request, true);
        return toRuleResponse(ruleRepository.save(rule), today());
    }

    @Transactional
    public RecurringLedgerRuleResponse updateRule(Long userId, Long ruleId, RecurringLedgerRuleRequest request) {
        RecurringLedgerRule rule = ruleRepository.findByIdAndOwnerId(ruleId, userId)
                .orElseThrow(() -> new NotFoundException("정기 입출금 규칙을 찾을 수 없습니다."));
        applyRequest(userId, rule, request, false);
        return toRuleResponse(rule, today());
    }

    @Transactional
    public void deactivateRule(Long userId, Long ruleId) {
        RecurringLedgerRule rule = ruleRepository.findByIdAndOwnerId(ruleId, userId)
                .orElseThrow(() -> new NotFoundException("정기 입출금 규칙을 찾을 수 없습니다."));
        rule.setActive(false);
    }

    @Transactional
    public void deleteRule(Long userId, Long ruleId) {
        RecurringLedgerRule rule = ruleRepository.findByIdAndOwnerId(ruleId, userId)
                .orElseThrow(() -> new NotFoundException("정기 입출금 규칙을 찾을 수 없습니다."));
        if (rule.isActive()) {
            throw new BadRequestException("사용 중인 정기 입출금은 먼저 일시정지해 주세요.");
        }
        occurrenceRepository.deleteAllByRuleId(ruleId);
        ruleRepository.delete(rule);
    }

    public List<RecurringLedgerOccurrenceResponse> listPendingOccurrences(Long userId) {
        appUserService.getRequiredUser(userId);
        return occurrenceRepository.findAllByRuleOwnerIdAndStatusOrderByScheduledDateAscIdAsc(
                        userId,
                        RecurringLedgerOccurrenceStatus.PENDING
                ).stream()
                .filter(occurrence -> occurrence.getMode() == RecurringLedgerMode.CONFIRM)
                .map(this::toOccurrenceResponse)
                .toList();
    }

    @Transactional
    public RecurringLedgerOccurrenceResponse approveOccurrence(Long userId, Long occurrenceId) {
        RecurringLedgerOccurrence occurrence = getPendingOccurrence(userId, occurrenceId);
        LedgerEntryResponse createdEntry = ledgerEntryService.create(
                userId,
                toEntryRequest(occurrence.getRule(), occurrence.getScheduledDate())
        );
        occurrence.setCreatedEntryId(createdEntry.id());
        occurrence.setStatus(RecurringLedgerOccurrenceStatus.CREATED);
        occurrence.setProcessedAt(now());
        return toOccurrenceResponse(occurrence);
    }

    @Transactional
    public RecurringLedgerOccurrenceResponse skipOccurrence(Long userId, Long occurrenceId) {
        RecurringLedgerOccurrence occurrence = getPendingOccurrence(userId, occurrenceId);
        occurrence.setStatus(RecurringLedgerOccurrenceStatus.SKIPPED);
        occurrence.setProcessedAt(now());
        return toOccurrenceResponse(occurrence);
    }

    @Transactional
    public synchronized int processDueDate(LocalDate date) {
        LocalDate targetDate = date != null ? date : today();
        int processedCount = 0;
        for (RecurringLedgerRule rule : ruleRepository.findAllByActiveTrueAndStartDateLessThanEqual(targetDate)) {
            if (!RecurringLedgerSchedule.isDue(
                    scheduleTypeOf(rule),
                    rule.getDayOfMonth(),
                    monthIntervalOf(rule),
                    rule.getIntervalDays(),
                    rule.getStartDate(),
                    rule.getEndDate(),
                    targetDate
            )) {
                continue;
            }

            RecurringLedgerOccurrence occurrence = occurrenceRepository
                    .findByRuleIdAndScheduledDate(rule.getId(), targetDate)
                    .orElseGet(() -> createPendingOccurrence(rule, targetDate));

            if (occurrence.getStatus() != RecurringLedgerOccurrenceStatus.PENDING) {
                continue;
            }

            if (occurrence.getMode() == RecurringLedgerMode.CONFIRM) {
                processedCount++;
                continue;
            }

            LedgerEntryResponse createdEntry = ledgerEntryService.create(
                    rule.getOwner().getId(),
                    toEntryRequest(rule, targetDate)
            );
            occurrence.setCreatedEntryId(createdEntry.id());
            occurrence.setStatus(RecurringLedgerOccurrenceStatus.CREATED);
            occurrence.setProcessedAt(now());
            occurrenceRepository.save(occurrence);
            processedCount++;
        }
        return processedCount;
    }

    private RecurringLedgerOccurrence getPendingOccurrence(Long userId, Long occurrenceId) {
        RecurringLedgerOccurrence occurrence = occurrenceRepository.findByIdAndRuleOwnerId(occurrenceId, userId)
                .orElseThrow(() -> new NotFoundException("정기 입출금 대기 내역을 찾을 수 없습니다."));
        if (occurrence.getStatus() != RecurringLedgerOccurrenceStatus.PENDING
                || occurrence.getMode() != RecurringLedgerMode.CONFIRM) {
            throw new BadRequestException("처리할 수 없는 정기 입출금 대기 내역입니다.");
        }
        return occurrence;
    }

    private RecurringLedgerOccurrence createPendingOccurrence(RecurringLedgerRule rule, LocalDate date) {
        RecurringLedgerOccurrence occurrence = new RecurringLedgerOccurrence();
        occurrence.setRule(rule);
        occurrence.setScheduledDate(date);
        occurrence.setMode(rule.getMode());
        occurrence.setStatus(RecurringLedgerOccurrenceStatus.PENDING);
        return occurrenceRepository.saveAndFlush(occurrence);
    }

    private void applyRequest(Long userId, RecurringLedgerRule rule, RecurringLedgerRuleRequest request, boolean creating) {
        RecurringLedgerScheduleType scheduleType = request.scheduleType() == null
                ? RecurringLedgerScheduleType.MONTHLY_DATE
                : request.scheduleType();
        validateSchedule(request, scheduleType);

        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BadRequestException("종료일은 시작일보다 빠를 수 없습니다.");
        }

        CategoryGroup categoryGroup = categoryGroupRepository.findByIdAndOwnerId(request.categoryGroupId(), userId)
                .orElseThrow(() -> new NotFoundException("대분류를 찾을 수 없습니다."));
        if (categoryGroup.getEntryType() != request.entryType()) {
            throw new BadRequestException("대분류의 수입/지출 구분이 거래 타입과 일치하지 않습니다.");
        }

        CategoryDetail categoryDetail = null;
        if (request.categoryDetailId() != null) {
            categoryDetail = categoryDetailRepository.findByIdAndGroupOwnerId(request.categoryDetailId(), userId)
                    .orElseThrow(() -> new NotFoundException("소분류를 찾을 수 없습니다."));
            if (!categoryDetail.getGroup().getId().equals(categoryGroup.getId())) {
                throw new BadRequestException("소분류가 선택한 대분류에 속하지 않습니다.");
            }
        }

        PaymentMethod paymentMethod = null;
        if (request.entryType() == EntryType.EXPENSE) {
            if (request.paymentMethodId() == null) {
                throw new BadRequestException("지출 정기 입출금은 결제수단을 선택해 주세요.");
            }
            paymentMethod = paymentMethodRepository.findByIdAndOwnerId(request.paymentMethodId(), userId)
                    .orElseThrow(() -> new NotFoundException("결제수단을 찾을 수 없습니다."));
        }

        LedgerEntryTextSanitizer.SanitizedLedgerText sanitizedText = LedgerEntryTextSanitizer.sanitize(
                request.title(),
                request.memo()
        );
        rule.setTitle(sanitizedText.title());
        rule.setMemo(sanitizedText.memo());
        rule.setAmount(request.amount());
        rule.setEntryType(request.entryType());
        rule.setScheduleType(scheduleType);
        rule.setMonthInterval(scheduleType == RecurringLedgerScheduleType.MONTHLY_DATE
                ? (request.monthInterval() == null ? 1 : request.monthInterval()) : null);
        rule.setDayOfMonth(scheduleType == RecurringLedgerScheduleType.MONTHLY_DATE ? request.dayOfMonth() : null);
        rule.setIntervalDays(scheduleType == RecurringLedgerScheduleType.EVERY_N_DAYS ? request.intervalDays() : null);
        rule.setStartDate(request.startDate());
        rule.setEndDate(request.endDate());
        rule.setMode(request.mode());
        rule.setCategoryGroup(categoryGroup);
        rule.setCategoryDetail(categoryDetail);
        rule.setPaymentMethod(paymentMethod);
        if (creating || request.active() != null) {
            rule.setActive(request.active() == null || request.active());
        }
    }

    private void validateSchedule(RecurringLedgerRuleRequest request, RecurringLedgerScheduleType scheduleType) {
        if (scheduleType == RecurringLedgerScheduleType.MONTHLY_DATE) {
            if (request.dayOfMonth() == null || request.dayOfMonth() < 1 || request.dayOfMonth() > 31) {
                throw new BadRequestException("매월 반복 날짜는 1일부터 31일 사이로 입력해 주세요.");
            }
            int monthInterval = request.monthInterval() == null ? 1 : request.monthInterval();
            if (monthInterval < 1 || monthInterval > RecurringLedgerSchedule.MAX_MONTH_INTERVAL) {
                throw new BadRequestException("달 반복 간격은 1개월부터 120개월 사이로 입력해 주세요.");
            }
            return;
        }

        if (request.intervalDays() == null
                || request.intervalDays() < 1
                || request.intervalDays() > RecurringLedgerSchedule.MAX_INTERVAL_DAYS) {
            throw new BadRequestException("반복 간격은 1일부터 3650일 사이로 입력해 주세요.");
        }
    }

    private LedgerEntryRequest toEntryRequest(RecurringLedgerRule rule, LocalDate date) {
        return new LedgerEntryRequest(
                date,
                null,
                rule.getTitle(),
                rule.getMemo(),
                rule.getAmount(),
                null,
                null,
                null,
                rule.getEntryType(),
                rule.getCategoryGroup().getId(),
                rule.getCategoryDetail() != null ? rule.getCategoryDetail().getId() : null,
                rule.getPaymentMethod() != null ? rule.getPaymentMethod().getId() : null,
                null,
                null
        );
    }

    private RecurringLedgerRuleResponse toRuleResponse(RecurringLedgerRule rule, LocalDate from) {
        return new RecurringLedgerRuleResponse(
                rule.getId(),
                rule.getTitle(),
                rule.getMemo(),
                rule.getAmount(),
                rule.getEntryType(),
                scheduleTypeOf(rule),
                monthIntervalOf(rule),
                rule.getDayOfMonth(),
                rule.getIntervalDays(),
                rule.getStartDate(),
                rule.getEndDate(),
                rule.getMode(),
                rule.isActive(),
                rule.getCategoryGroup().getId(),
                rule.getCategoryGroup().getName(),
                rule.getCategoryDetail() != null ? rule.getCategoryDetail().getId() : null,
                rule.getCategoryDetail() != null ? rule.getCategoryDetail().getName() : null,
                rule.getPaymentMethod() != null ? rule.getPaymentMethod().getId() : null,
                rule.getPaymentMethod() != null ? rule.getPaymentMethod().getName() : "-",
                rule.isActive()
                        ? RecurringLedgerSchedule.nextDueDate(
                                scheduleTypeOf(rule),
                                rule.getDayOfMonth(),
                                monthIntervalOf(rule),
                                rule.getIntervalDays(),
                                rule.getStartDate(),
                                rule.getEndDate(),
                                from
                        )
                        : null,
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }

    private RecurringLedgerOccurrenceResponse toOccurrenceResponse(RecurringLedgerOccurrence occurrence) {
        RecurringLedgerRule rule = occurrence.getRule();
        return new RecurringLedgerOccurrenceResponse(
                occurrence.getId(),
                rule.getId(),
                rule.getTitle(),
                occurrence.getScheduledDate(),
                rule.getEntryType(),
                rule.getAmount(),
                occurrence.getMode(),
                occurrence.getStatus(),
                occurrence.getCreatedEntryId(),
                occurrence.getProcessedAt()
        );
    }

    private LocalDate today() {
        return LocalDate.now(DEFAULT_ZONE);
    }

    private Integer monthIntervalOf(RecurringLedgerRule rule) {
        if (scheduleTypeOf(rule) != RecurringLedgerScheduleType.MONTHLY_DATE) {
            return null;
        }
        return rule.getMonthInterval() == null ? 1 : rule.getMonthInterval();
    }

    private RecurringLedgerScheduleType scheduleTypeOf(RecurringLedgerRule rule) {
        return rule.getScheduleType() == null
                ? RecurringLedgerScheduleType.MONTHLY_DATE
                : rule.getScheduleType();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(DEFAULT_ZONE);
    }
}
