package com.playdata.calen.ledger.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.dto.PaymentMethodRequest;
import com.playdata.calen.ledger.dto.PaymentMethodResponse;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
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
        AppUser owner = appUserService.getRequiredUser(userId);
        return paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public PaymentMethodResponse create(Long userId, PaymentMethodRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setOwner(owner);
        paymentMethod.setName(request.name());
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

    private PaymentMethodResponse toResponse(PaymentMethod paymentMethod) {
        return new PaymentMethodResponse(
                paymentMethod.getId(),
                paymentMethod.getName(),
                paymentMethod.getKind(),
                paymentMethod.getDisplayOrder(),
                paymentMethod.isActive()
        );
    }
}
