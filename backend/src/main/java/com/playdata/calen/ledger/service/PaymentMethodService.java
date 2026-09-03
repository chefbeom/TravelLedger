package com.playdata.calen.ledger.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.dto.PaymentMethodRequest;
import com.playdata.calen.ledger.dto.PaymentMethodResponse;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.dto.DisplayOrderRequest;
import com.playdata.calen.ledger.dto.LedgerClassificationDeleteRequest;
import com.playdata.calen.ledger.dto.LedgerClassificationUsageEntryResponse;
import com.playdata.calen.ledger.dto.LedgerClassificationUsageResponse;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentMethodService {

    private static final String UNCATEGORIZED_NAME = "미분류";
    private static final int USAGE_PREVIEW_SIZE = 30;

    private final AppUserService appUserService;
    private final PaymentMethodRepository paymentMethodRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public List<PaymentMethodResponse> getPaymentMethods(Long userId) {
        return getPaymentMethods(userId, false);
    }

    public List<PaymentMethodResponse> getPaymentMethods(Long userId, boolean includeInactive) {
        AppUser owner = appUserService.getRequiredUser(userId);
        List<PaymentMethod> paymentMethods = includeInactive
                ? paymentMethodRepository.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(owner.getId())
                : paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId());

        return paymentMethods.stream()
                .sorted(Comparator.comparing(PaymentMethod::isActive).reversed()
                        .thenComparing(PaymentMethod::getDisplayOrder)
                        .thenComparing(PaymentMethod::getId))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public List<PaymentMethodResponse> reorder(Long userId, DisplayOrderRequest request) {
        appUserService.getRequiredUser(userId);
        List<PaymentMethod> paymentMethods = paymentMethodRepository.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(userId);
        List<Long> orderedIds = request == null ? null : request.orderedIds();
        validateExactOrder(orderedIds, paymentMethods.stream().map(PaymentMethod::getId).toList());

        Map<Long, PaymentMethod> paymentMethodsById = paymentMethods.stream()
                .collect(Collectors.toMap(PaymentMethod::getId, Function.identity()));
        for (int index = 0; index < orderedIds.size(); index++) {
            paymentMethodsById.get(orderedIds.get(index)).setDisplayOrder(index);
        }
        return getPaymentMethods(userId, true);
    }

    @Transactional
    public PaymentMethodResponse create(Long userId, PaymentMethodRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        String name = normalizeName(request.name());
        if (paymentMethodRepository.findFirstByOwnerIdAndNameIgnoreCaseOrderByIdAsc(owner.getId(), name).isPresent()) {
            throw new BadRequestException("이미 있는 결제수단입니다.");
        }
        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setOwner(owner);
        paymentMethod.setName(name);
        paymentMethod.setKind(request.kind());
        paymentMethod.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
        paymentMethod.setActive(true);
        return toResponse(paymentMethodRepository.save(paymentMethod));
    }

    @Transactional
    public void deactivate(Long userId, Long id) {
        PaymentMethod paymentMethod = paymentMethodRepository.findByIdAndOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("결제수단을 찾을 수 없습니다."));
        paymentMethod.setActive(false);
    }

    @Transactional
    public PaymentMethodResponse activate(Long userId, Long id) {
        PaymentMethod paymentMethod = paymentMethodRepository.findByIdAndOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("결제수단을 찾을 수 없습니다."));
        paymentMethod.setActive(true);
        return toResponse(paymentMethod);
    }

    public LedgerClassificationUsageResponse getUsage(Long userId, Long id) {
        PaymentMethod paymentMethod = paymentMethodRepository.findByIdAndOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("결제수단을 찾을 수 없습니다."));
        Page<LedgerEntry> page = ledgerEntryRepository.findAllByOwnerIdAndPaymentMethodIdOrderByEntryDateDescIdDesc(
                userId,
                id,
                PageRequest.of(0, USAGE_PREVIEW_SIZE)
        );
        long totalCount = ledgerEntryRepository.countByOwnerIdAndPaymentMethodId(userId, id);
        return new LedgerClassificationUsageResponse(
                "PAYMENT_METHOD",
                paymentMethod.getId(),
                paymentMethod.getName(),
                totalCount,
                totalCount > page.getNumberOfElements(),
                page.getContent().stream()
                        .map(this::toUsageEntryResponse)
                        .toList()
        );
    }

    @Transactional
    public void delete(Long userId, Long id, LedgerClassificationDeleteRequest request) {
        LedgerClassificationDeleteRequest deleteRequest = normalizeDeleteRequest(request);
        PaymentMethod paymentMethod = paymentMethodRepository.findByIdAndOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("결제수단을 찾을 수 없습니다."));
        PaymentMethod replacementPaymentMethod = resolveReplacementPaymentMethod(userId, id, deleteRequest.replacementPaymentMethodId());

        ledgerEntryRepository.findAllByOwnerIdAndPaymentMethodId(userId, id)
                .forEach(entry -> entry.setPaymentMethod(replacementPaymentMethod));

        paymentMethodRepository.delete(paymentMethod);
    }

    private PaymentMethodResponse toResponse(PaymentMethod paymentMethod) {
        return new PaymentMethodResponse(
                paymentMethod.getId(),
                paymentMethod.getName(),
                paymentMethod.getKind(),
                paymentMethod.getDisplayOrder(),
                paymentMethod.isActive()
        );
    }

    private LedgerClassificationUsageEntryResponse toUsageEntryResponse(LedgerEntry entry) {
        return new LedgerClassificationUsageEntryResponse(
                entry.getId(),
                entry.getEntryDate(),
                entry.getEntryTime(),
                entry.getTitle(),
                entry.getAmount(),
                entry.getEntryType(),
                entry.getCategoryGroup().getName(),
                entry.getCategoryDetail() != null ? entry.getCategoryDetail().getName() : null,
                entry.getPaymentMethod().getName(),
                entry.getDeletedAt() != null
        );
    }

    private PaymentMethod resolveReplacementPaymentMethod(Long userId, Long deletedPaymentMethodId, Long replacementPaymentMethodId) {
        if (replacementPaymentMethodId != null) {
            if (replacementPaymentMethodId.equals(deletedPaymentMethodId)) {
                throw new BadRequestException("삭제할 결제수단은 대체 결제수단으로 사용할 수 없습니다.");
            }
            PaymentMethod replacementPaymentMethod = paymentMethodRepository.findByIdAndOwnerId(replacementPaymentMethodId, userId)
                    .orElseThrow(() -> new NotFoundException("대체 결제수단을 찾을 수 없습니다."));
            replacementPaymentMethod.setActive(true);
            return replacementPaymentMethod;
        }
        return resolveUncategorizedPaymentMethod(userId, deletedPaymentMethodId);
    }

    private PaymentMethod resolveUncategorizedPaymentMethod(Long userId, Long excludedPaymentMethodId) {
        return paymentMethodRepository.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(userId).stream()
                .filter(paymentMethod -> !paymentMethod.getId().equals(excludedPaymentMethodId))
                .filter(paymentMethod -> UNCATEGORIZED_NAME.equalsIgnoreCase(paymentMethod.getName()))
                .findFirst()
                .map(paymentMethod -> {
                    paymentMethod.setActive(true);
                    return paymentMethod;
                })
                .orElseGet(() -> createUncategorizedPaymentMethod(userId));
    }

    private PaymentMethod createUncategorizedPaymentMethod(Long userId) {
        AppUser owner = appUserService.getRequiredUser(userId);
        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setOwner(owner);
        paymentMethod.setName(UNCATEGORIZED_NAME);
        paymentMethod.setKind(PaymentMethodKind.OTHER);
        paymentMethod.setDisplayOrder(Integer.MAX_VALUE);
        paymentMethod.setActive(true);
        return paymentMethodRepository.save(paymentMethod);
    }

    private LedgerClassificationDeleteRequest normalizeDeleteRequest(LedgerClassificationDeleteRequest request) {
        return request == null ? new LedgerClassificationDeleteRequest(null, null, null) : request;
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }

    private void validateExactOrder(List<Long> orderedIds, List<Long> expectedIds) {
        if (orderedIds == null || orderedIds.isEmpty()) {
            throw new BadRequestException("순서 목록은 비어 있을 수 없습니다.");
        }
        Set<Long> requestedIds = new HashSet<>(orderedIds);
        Set<Long> existingIds = new HashSet<>(expectedIds);
        if (orderedIds.size() != expectedIds.size()
                || requestedIds.size() != orderedIds.size()
                || !requestedIds.equals(existingIds)) {
            throw new BadRequestException("내 계정의 모든 결제수단을 포함한 순서 목록이 필요합니다.");
        }
    }
}
