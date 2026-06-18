package com.playdata.calen.ledger.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.dto.PaymentMethodRequest;
import com.playdata.calen.ledger.dto.PaymentMethodResponse;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentMethodService {

    private final AppUserService appUserService;
    private final PaymentMethodRepository paymentMethodRepository;

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

    private PaymentMethodResponse toResponse(PaymentMethod paymentMethod) {
        return new PaymentMethodResponse(
                paymentMethod.getId(),
                paymentMethod.getName(),
                paymentMethod.getKind(),
                paymentMethod.getDisplayOrder(),
                paymentMethod.isActive()
        );
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }
}
