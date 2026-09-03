package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.dto.DisplayOrderRequest;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryDisplayOrderServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private AppUserService appUserService;

    @Mock
    private CategoryGroupRepository categoryGroupRepository;

    @Mock
    private CategoryDetailRepository categoryDetailRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private CategoryService service;

    @BeforeEach
    void setUp() {
        service = new CategoryService(
                appUserService,
                categoryGroupRepository,
                categoryDetailRepository,
                ledgerEntryRepository
        );
    }

    @Test
    void reorderGroupsStoresTheRequestedOrderForTheWholeUserCatalog() {
        AppUser owner = owner();
        CategoryGroup food = group(10L, "식비", EntryType.EXPENSE, 0);
        CategoryGroup salary = group(20L, "급여", EntryType.INCOME, 1);
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(owner);
        when(categoryGroupRepository.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(USER_ID))
                .thenReturn(List.of(food, salary));

        var response = service.reorderGroups(USER_ID, new DisplayOrderRequest(List.of(20L, 10L)));

        assertThat(salary.getDisplayOrder()).isZero();
        assertThat(food.getDisplayOrder()).isEqualTo(1);
        assertThat(response).extracting("id").containsExactly(20L, 10L);
    }

    @Test
    void reorderGroupsRejectsAnIncompleteOrDuplicatedOrder() {
        owner();
        CategoryGroup food = group(10L, "식비", EntryType.EXPENSE, 0);
        CategoryGroup salary = group(20L, "급여", EntryType.INCOME, 1);
        when(categoryGroupRepository.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(USER_ID))
                .thenReturn(List.of(food, salary));

        assertThatThrownBy(() -> service.reorderGroups(USER_ID, new DisplayOrderRequest(List.of(10L, 10L))))
                .isInstanceOf(BadRequestException.class);

        assertThat(food.getDisplayOrder()).isZero();
        assertThat(salary.getDisplayOrder()).isEqualTo(1);
    }

    @Test
    void reorderDetailsScopesTheOrderToTheSelectedGroup() {
        CategoryGroup food = group(10L, "식비", EntryType.EXPENSE, 0);
        CategoryDetail meal = detail(101L, food, "식사", 0);
        CategoryDetail snack = detail(102L, food, "간식", 1);
        when(categoryGroupRepository.findByIdAndOwnerId(10L, USER_ID)).thenReturn(Optional.of(food));
        when(categoryDetailRepository.findAllByGroupIdOrderByDisplayOrderAscIdAsc(10L))
                .thenReturn(List.of(meal, snack));

        var response = service.reorderDetails(USER_ID, 10L, new DisplayOrderRequest(List.of(102L, 101L)));

        assertThat(snack.getDisplayOrder()).isZero();
        assertThat(meal.getDisplayOrder()).isEqualTo(1);
        assertThat(response).extracting("id").containsExactly(102L, 101L);
    }

    private AppUser owner() {
        AppUser owner = new AppUser();
        owner.setId(USER_ID);
        owner.setLoginId("owner");
        owner.setDisplayName("Owner");
        owner.setActive(true);
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(owner);
        return owner;
    }

    private CategoryGroup group(Long id, String name, EntryType entryType, int displayOrder) {
        CategoryGroup group = new CategoryGroup();
        group.setId(id);
        group.setOwner(ownerWithoutMock());
        group.setName(name);
        group.setEntryType(entryType);
        group.setDisplayOrder(displayOrder);
        group.setActive(true);
        return group;
    }

    private CategoryDetail detail(Long id, CategoryGroup group, String name, int displayOrder) {
        CategoryDetail detail = new CategoryDetail();
        detail.setId(id);
        detail.setGroup(group);
        detail.setName(name);
        detail.setDisplayOrder(displayOrder);
        detail.setActive(true);
        return detail;
    }

    private AppUser ownerWithoutMock() {
        AppUser owner = new AppUser();
        owner.setId(USER_ID);
        return owner;
    }
}
