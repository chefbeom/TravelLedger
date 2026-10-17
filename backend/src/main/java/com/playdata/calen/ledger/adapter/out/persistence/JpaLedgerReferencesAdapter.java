package com.playdata.calen.ledger.adapter.out.persistence;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.application.port.out.LedgerReferencesPort;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.domain.model.LedgerPreparationData.*;
import com.playdata.calen.ledger.repository.*;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaLedgerReferencesAdapter implements LedgerReferencesPort {
    private final CategoryGroupRepository groups;
    private final CategoryDetailRepository details;
    private final PaymentMethodRepository payments;
    private final TravelPlanRepository travelPlans;
    private final AppUserService users;

    @Override public Category category(Long owner, Long id) {
        var group = groups.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("대분류를 찾을 수 없습니다."));
        return new Category(group.getId(), group.getEntryType());
    }
    @Override public Detail detail(Long owner, Long id) {
        var detail = details.findByIdAndGroupOwnerId(id, owner).orElseThrow(() -> new NotFoundException("소분류를 찾을 수 없습니다."));
        return new Detail(detail.getId(), detail.getGroup().getId());
    }
    @Override public Payment payment(Long owner, EntryType type, Long id) {
        PaymentMethod payment;
        if (type == EntryType.INCOME) {
            payment = payments.findByOwnerIdAndNameIgnoreCase(owner, "-").orElseGet(() -> {
                var created = new PaymentMethod();
                created.setOwner(users.getRequiredUser(owner));
                created.setName("-");
                created.setKind(PaymentMethodKind.OTHER);
                created.setDisplayOrder(Integer.MAX_VALUE);
                created.setActive(false);
                return payments.save(created);
            });
        } else {
            if (id == null) throw new BadRequestException("결제수단을 선택해 주세요.");
            payment = payments.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("결제수단을 찾을 수 없습니다."));
        }
        return new Payment(payment.getId(), payment.getName(), payment.getKind());
    }
    @Override public TravelPeriod travelPeriod(Long owner, Long id) {
        var plan = travelPlans.findByIdAndOwnerId(id, owner).orElseThrow(() -> new BadRequestException("Travel plan not found."));
        return new TravelPeriod(plan.getStartDate(), plan.getEndDate());
    }
}
