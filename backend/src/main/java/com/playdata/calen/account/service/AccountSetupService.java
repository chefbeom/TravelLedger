package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AccountSetupService {

    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final PaymentMethodRepository paymentMethodRepository;

    public void initializeDefaults(AppUser owner) {
        if (categoryGroupRepository.existsByOwnerId(owner.getId()) || paymentMethodRepository.existsByOwnerId(owner.getId())) {
            return;
        }

        CategoryGroup food = createGroup(owner, "식비", EntryType.EXPENSE, 1);
        CategoryGroup transport = createGroup(owner, "교통", EntryType.EXPENSE, 2);
        CategoryGroup living = createGroup(owner, "생활", EntryType.EXPENSE, 3);
        CategoryGroup salary = createGroup(owner, "급여", EntryType.INCOME, 1);
        CategoryGroup sideIncome = createGroup(owner, "부수입", EntryType.INCOME, 2);

        createDetail(food, "외식", 1);
        createDetail(food, "군것질", 2);
        createDetail(food, "장보기", 3);
        createDetail(transport, "대중교통", 1);
        createDetail(transport, "택시", 2);
        createDetail(living, "쇼핑", 1);
        createDetail(living, "의료", 2);
        createDetail(salary, "본급", 1);
        createDetail(salary, "보너스", 2);
        createDetail(sideIncome, "프리랜서", 1);
        createDetail(sideIncome, "환급", 2);

        createPaymentMethod(owner, "신한카드", PaymentMethodKind.CARD, 1);
        createPaymentMethod(owner, "우리카드", PaymentMethodKind.CARD, 2);
        createPaymentMethod(owner, "현금", PaymentMethodKind.CASH, 3);
        createPaymentMethod(owner, "포인트", PaymentMethodKind.POINT, 4);
        createPaymentMethod(owner, "계좌이체", PaymentMethodKind.TRANSFER, 5);
    }

    private CategoryGroup createGroup(AppUser owner, String name, EntryType entryType, int displayOrder) {
        CategoryGroup group = new CategoryGroup();
        group.setOwner(owner);
        group.setName(name);
        group.setEntryType(entryType);
        group.setDisplayOrder(displayOrder);
        group.setActive(true);
        return categoryGroupRepository.save(group);
    }

    private CategoryDetail createDetail(CategoryGroup group, String name, int displayOrder) {
        CategoryDetail detail = new CategoryDetail();
        detail.setGroup(group);
        detail.setName(name);
        detail.setDisplayOrder(displayOrder);
        detail.setActive(true);
        return categoryDetailRepository.save(detail);
    }

    private PaymentMethod createPaymentMethod(AppUser owner, String name, PaymentMethodKind kind, int displayOrder) {
        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setOwner(owner);
        paymentMethod.setName(name);
        paymentMethod.setKind(kind);
        paymentMethod.setDisplayOrder(displayOrder);
        paymentMethod.setActive(true);
        return paymentMethodRepository.save(paymentMethod);
    }
}
