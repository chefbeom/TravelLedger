package com.playdata.calen.ledger.service;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerAdjustmentType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.dto.LedgerAdjustmentRequest;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** All writes are serialized by the same owner lock used by ordinary ledger mutations. */
@Service
@RequiredArgsConstructor
public class LedgerAdjustmentService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999.99");
    private final AppUserService users;
    private final LedgerEntryRepository entries;
    private final CategoryGroupRepository groups;
    private final PaymentMethodRepository payments;
    private final EntityManager entities;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long create(Long ownerId, Long originalId, LedgerAdjustmentRequest request) {
        var owner = users.getRequiredUserForWrite(ownerId);
        if (request == null || request.type() == null || request.date() == null || request.requestKey() == null) {
            throw new BadRequestException("취소 또는 환불 정보를 확인해 주세요.");
        }
        String memo = request.memo() == null ? "" : request.memo().trim();
        if (memo.length() > 500) throw new BadRequestException("메모는 500자 이내로 입력해 주세요.");
        BigDecimal principal = request.type() == LedgerAdjustmentType.REFUND ? money(request.principal(), false) : null;
        BigDecimal deduction = request.type() == LedgerAdjustmentType.REFUND ? money(request.deduction(), true) : null;
        BigDecimal extra = request.type() == LedgerAdjustmentType.REFUND ? money(request.extra(), true) : null;

        var existing = entries.findByOwnerIdAndAdjustmentRequestKey(ownerId, request.requestKey().toString());
        if (existing.isPresent()) {
            LedgerEntry event = existing.get();
            boolean same = Objects.equals(event.getAdjustmentOriginId(), originalId)
                    && event.getAdjustmentType() == request.type() && Objects.equals(event.getEntryDate(), request.date())
                    && Objects.equals(event.getEntryTime(), request.time()) && Objects.equals(text(event.getMemo()), memo);
            if (request.type() == LedgerAdjustmentType.REFUND) {
                same = same && amountEquals(event.getAdjustmentPrincipal(), principal)
                        && amountEquals(event.getAdjustmentDeduction(), deduction) && amountEquals(event.getAdjustmentExtra(), extra)
                        && Objects.equals(event.getCategoryGroup().getId(), request.incomeCategoryGroupId())
                        && Objects.equals(event.getPaymentMethod().getId(), request.receivingPaymentMethodId());
            }
            if (!same) throw new BadRequestException("이미 사용한 요청 번호입니다. 내용을 다시 확인해 주세요.");
            return event.getId();
        }

        LedgerEntry original = entries.lockActiveOwned(originalId, ownerId)
                .orElseThrow(() -> new NotFoundException("원거래를 찾을 수 없습니다."));
        entities.refresh(original, LockModeType.PESSIMISTIC_WRITE);
        if (original.getEntryType() != EntryType.EXPENSE || original.getAdjustmentType() != null
                || original.isFinanciallyExcluded() || original.getCancelledAt() != null) {
            throw new BadRequestException("취소 또는 환불할 수 있는 지출 거래가 아닙니다.");
        }
        if (request.date().isBefore(original.getEntryDate())) {
            throw new BadRequestException("처리일은 원거래 날짜보다 빠를 수 없습니다.");
        }
        BigDecimal alreadyRefunded = original.getRefundedPrincipal() == null ? BigDecimal.ZERO : original.getRefundedPrincipal();
        if (alreadyRefunded.signum() < 0 || alreadyRefunded.compareTo(original.getAmount()) > 0) {
            throw new BadRequestException("원거래의 환불 상태를 확인할 수 없습니다.");
        }

        LedgerEntry event = new LedgerEntry();
        event.setOwner(owner);
        event.setEntryDate(request.date());
        event.setEntryTime(request.time());
        event.setMemo(memo.isEmpty() ? null : memo);
        event.setAdjustmentType(request.type());
        event.setAdjustmentOriginId(originalId);
        event.setAdjustmentRequestKey(request.requestKey().toString());
        if (request.type() == LedgerAdjustmentType.PAYMENT_CANCELLATION) {
            if (alreadyRefunded.signum() != 0) throw new BadRequestException("환불 기록이 있는 거래는 결제 취소할 수 없습니다. 환불 기록을 먼저 되돌려 주세요.");
            if (original.getTravelRecordId() != null) {
                throw new BadRequestException("여행 기록과 연결된 거래는 여행 지출과 금액이 달라질 수 있어 결제 취소할 수 없습니다.");
            }
            original.setFinanciallyExcluded(true);
            original.setCancelledAt(LocalDateTime.of(request.date(), request.time() == null ? LocalTime.MIDNIGHT : request.time()));
            event.setFinanciallyExcluded(true);
            event.setEntryType(EntryType.EXPENSE);
            event.setTitle(title("결제 취소: ", original.getTitle()));
            event.setAmount(original.getAmount());
            event.setAdjustmentPrincipal(original.getAmount());
            event.setCategoryGroup(original.getCategoryGroup());
            event.setCategoryDetail(original.getCategoryDetail());
            event.setPaymentMethod(original.getPaymentMethod());
            event.setForeignCurrencyCode(original.getForeignCurrencyCode());
            event.setForeignAmount(original.getForeignAmount());
            event.setExchangeRateToKrw(original.getExchangeRateToKrw());
            event.setExchangeRateDate(original.getExchangeRateDate());
            event.setExchangeRateProvider(original.getExchangeRateProvider());
        } else {
            if (principal.add(alreadyRefunded).compareTo(original.getAmount()) > 0) {
                throw new BadRequestException("환불 기준금액 합계는 원거래 금액을 초과할 수 없습니다. 추가 지급액은 별도로 입력해 주세요.");
            }
            BigDecimal received = principal.subtract(deduction).add(extra);
            if (received.signum() < 0 || received.compareTo(MAX_AMOUNT) > 0) {
                throw new BadRequestException("차감액을 반영한 실제 환불액을 확인해 주세요.");
            }
            if (request.incomeCategoryGroupId() == null || request.receivingPaymentMethodId() == null) {
                throw new BadRequestException("수입 대분류와 실제 수령 수단을 선택해 주세요.");
            }
            var group = groups.findByIdAndOwnerId(request.incomeCategoryGroupId(), ownerId)
                    .filter(item -> item.isActive() && item.getEntryType() == EntryType.INCOME)
                    .orElseThrow(() -> new BadRequestException("사용 중인 수입 대분류를 선택해 주세요."));
            var payment = payments.findByIdAndOwnerId(request.receivingPaymentMethodId(), ownerId)
                    .filter(item -> item.isActive() && !"-".equals(item.getName()))
                    .orElseThrow(() -> new BadRequestException("실제로 돌려받은 수단을 선택해 주세요."));
            original.setRefundedPrincipal(alreadyRefunded.add(principal));
            event.setEntryType(EntryType.INCOME);
            event.setTitle(title("환불: ", original.getTitle()));
            event.setAmount(received);
            event.setAdjustmentPrincipal(principal);
            event.setAdjustmentDeduction(deduction);
            event.setAdjustmentExtra(extra);
            event.setCategoryGroup(group);
            event.setPaymentMethod(payment);
        }
        original.setHasAdjustmentHistory(true);
        return entries.saveAndFlush(event).getId();
    }

    /** Reversals remain as excluded records, so an accidental adjustment never erases its audit trail. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revert(Long ownerId, Long adjustmentId) {
        users.getRequiredUserForWrite(ownerId);
        LedgerEntry event = entries.lockActiveOwned(adjustmentId, ownerId)
                .orElseThrow(() -> new NotFoundException("취소·환불 기록을 찾을 수 없습니다."));
        entities.refresh(event, LockModeType.PESSIMISTIC_WRITE);
        if (event.getAdjustmentType() == null || event.getAdjustmentOriginId() == null) {
            throw new BadRequestException("되돌릴 수 있는 취소·환불 기록이 아닙니다.");
        }
        if (event.getAdjustmentVoidedAt() != null) return;
        LedgerEntry original = entries.lockActiveOwned(event.getAdjustmentOriginId(), ownerId)
                .orElseThrow(() -> new BadRequestException("원거래를 확인할 수 없어 되돌릴 수 없습니다."));
        entities.refresh(original, LockModeType.PESSIMISTIC_WRITE);
        if (event.getAdjustmentType() == LedgerAdjustmentType.PAYMENT_CANCELLATION) {
            if (!original.isFinanciallyExcluded() || original.getCancelledAt() == null) throw inconsistent();
            original.setFinanciallyExcluded(false);
            original.setCancelledAt(null);
        } else {
            BigDecimal refunded = original.getRefundedPrincipal();
            BigDecimal principal = event.getAdjustmentPrincipal();
            if (refunded == null || principal == null || refunded.compareTo(principal) < 0 || original.isFinanciallyExcluded()) {
                throw inconsistent();
            }
            original.setRefundedPrincipal(refunded.subtract(principal));
        }
        event.setFinanciallyExcluded(true);
        event.setAdjustmentVoidedAt(LocalDateTime.now());
    }

    private BigDecimal money(BigDecimal value, boolean optional) {
        if (value == null && optional) return BigDecimal.ZERO.setScale(2);
        if (value == null || value.signum() < 0 || (!optional && value.signum() == 0)) {
            throw new BadRequestException("금액을 확인해 주세요.");
        }
        try {
            if (value.signum() == 0) return BigDecimal.ZERO.setScale(2);
            if (value.compareTo(MAX_AMOUNT) > 0 || value.stripTrailingZeros().scale() > 2) throw new ArithmeticException();
            BigDecimal normalized = value.setScale(2, RoundingMode.UNNECESSARY);
            return normalized;
        } catch (ArithmeticException invalid) {
            throw new BadRequestException("금액은 소수 둘째 자리와 허용 범위 내에서 입력해 주세요.");
        }
    }
    private boolean amountEquals(BigDecimal left, BigDecimal right) { return left != null && right != null && left.compareTo(right) == 0; }
    private String text(String value) { return value == null ? "" : value; }
    private String title(String prefix, String value) {
        String result = prefix + (value == null ? "" : value);
        return result.length() <= 120 ? result : result.substring(0, 120);
    }
    private BadRequestException inconsistent() { return new BadRequestException("원거래와 처리 기록이 일치하지 않습니다. 관리자에게 문의해 주세요."); }
}
