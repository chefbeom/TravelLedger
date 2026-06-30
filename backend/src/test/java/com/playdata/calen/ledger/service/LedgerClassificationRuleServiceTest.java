package com.playdata.calen.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerClassificationRule;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.dto.LedgerClassificationPreviewRequest;
import com.playdata.calen.ledger.dto.LedgerClassificationRuleRequest;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerClassificationRuleRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerClassificationRuleServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private AppUserService appUserService;

    @Mock
    private LedgerClassificationRuleRepository ledgerClassificationRuleRepository;

    @Mock
    private CategoryGroupRepository categoryGroupRepository;

    @Mock
    private CategoryDetailRepository categoryDetailRepository;

    @Mock
    private PaymentMethodRepository paymentMethodRepository;

    private LedgerClassificationRuleService service;

    @BeforeEach
    void setUp() {
        service = new LedgerClassificationRuleService(
                appUserService,
                ledgerClassificationRuleRepository,
                categoryGroupRepository,
                categoryDetailRepository,
                paymentMethodRepository
        );
    }

    @Test
    void previewReturnsFirstActiveOwnerRuleInPriorityOrder() {
        stubUser();
        CategoryGroup food = categoryGroup(10L, "Food", EntryType.EXPENSE);
        LedgerClassificationRule cafeRule = rule(2L, "starbucks", food, 10, EntryType.EXPENSE);
        LedgerClassificationRule broadRule = rule(3L, "coffee", food, 100, EntryType.EXPENSE);
        when(ledgerClassificationRuleRepository.findAllByOwnerIdAndActiveTrueOrderByPriorityAscIdAsc(USER_ID))
                .thenReturn(List.of(cafeRule, broadRule));

        var response = service.preview(USER_ID, new LedgerClassificationPreviewRequest(
                "Morning Starbucks",
                "coffee before work",
                EntryType.EXPENSE
        ));

        assertThat(response.matched()).isTrue();
        assertThat(response.reason()).isEqualTo("Matched keyword: starbucks");
        assertThat(response.rule().id()).isEqualTo(2L);
        assertThat(response.rule().priority()).isEqualTo(10);
        verify(ledgerClassificationRuleRepository).findAllByOwnerIdAndActiveTrueOrderByPriorityAscIdAsc(USER_ID);
    }

    @Test
    void previewDoesNotMatchDifferentEntryTypeRule() {
        stubUser();
        CategoryGroup income = categoryGroup(20L, "Income", EntryType.INCOME);
        LedgerClassificationRule salaryRule = rule(4L, "salary", income, 1, EntryType.INCOME);
        when(ledgerClassificationRuleRepository.findAllByOwnerIdAndActiveTrueOrderByPriorityAscIdAsc(USER_ID))
                .thenReturn(List.of(salaryRule));

        var response = service.preview(USER_ID, new LedgerClassificationPreviewRequest(
                "salary deposit",
                "monthly payroll",
                EntryType.EXPENSE
        ));

        assertThat(response.matched()).isFalse();
        assertThat(response.reason()).isEqualTo("No classification rule matched.");
        assertThat(response.rule()).isNull();
    }

    @Test
    void approveRecommendedRuleCreatesActiveOwnerRuleFromDraft() {
        stubUser();
        CategoryGroup food = categoryGroup(10L, "Food", EntryType.EXPENSE);
        when(categoryGroupRepository.findByIdAndOwnerId(10L, USER_ID)).thenReturn(Optional.of(food));
        when(ledgerClassificationRuleRepository.save(any(LedgerClassificationRule.class))).thenAnswer(invocation -> {
            LedgerClassificationRule saved = invocation.getArgument(0);
            saved.setId(88L);
            return saved;
        });

        var response = service.approveRecommendedRule(USER_ID, new LedgerClassificationRuleRequest(
                "Starbucks",
                EntryType.EXPENSE,
                10L,
                null,
                null,
                20,
                false
        ));

        assertThat(response.id()).isEqualTo(88L);
        assertThat(response.keyword()).isEqualTo("Starbucks");
        assertThat(response.normalizedKeyword()).isEqualTo("starbucks");
        assertThat(response.priority()).isEqualTo(20);
        assertThat(response.active()).isTrue();
        verify(ledgerClassificationRuleRepository).save(any(LedgerClassificationRule.class));
    }

    @Test
    void createRuleRejectsCategoryDetailFromDifferentGroup() {
        AppUser user = stubUser();
        CategoryGroup selectedGroup = categoryGroup(10L, "Food", EntryType.EXPENSE);
        CategoryGroup otherGroup = categoryGroup(11L, "Transport", EntryType.EXPENSE);
        CategoryDetail otherDetail = categoryDetail(30L, otherGroup, "Taxi");
        when(categoryGroupRepository.findByIdAndOwnerId(10L, USER_ID)).thenReturn(Optional.of(selectedGroup));
        when(categoryDetailRepository.findByIdAndGroupOwnerId(30L, USER_ID)).thenReturn(Optional.of(otherDetail));

        assertThatThrownBy(() -> service.createRule(USER_ID, new LedgerClassificationRuleRequest(
                "taxi",
                EntryType.EXPENSE,
                10L,
                30L,
                null,
                100,
                true
        )))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Category detail must belong to the selected category group.");

        verify(ledgerClassificationRuleRepository, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(user.getId()).isEqualTo(USER_ID);
    }

    private AppUser stubUser() {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        user.setLoginId("owner");
        user.setDisplayName("Owner");
        user.setActive(true);
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(user);
        return user;
    }

    private CategoryGroup categoryGroup(Long id, String name, EntryType entryType) {
        AppUser owner = new AppUser();
        owner.setId(USER_ID);
        CategoryGroup group = new CategoryGroup();
        group.setId(id);
        group.setOwner(owner);
        group.setName(name);
        group.setEntryType(entryType);
        group.setActive(true);
        return group;
    }

    private CategoryDetail categoryDetail(Long id, CategoryGroup group, String name) {
        CategoryDetail detail = new CategoryDetail();
        detail.setId(id);
        detail.setGroup(group);
        detail.setName(name);
        detail.setActive(true);
        return detail;
    }

    private LedgerClassificationRule rule(
            Long id,
            String keyword,
            CategoryGroup categoryGroup,
            int priority,
            EntryType entryType
    ) {
        LedgerClassificationRule rule = new LedgerClassificationRule();
        rule.setId(id);
        rule.setOwner(categoryGroup.getOwner());
        rule.setKeyword(keyword);
        rule.setNormalizedKeyword(keyword.toLowerCase(java.util.Locale.ROOT));
        rule.setEntryType(entryType);
        rule.setCategoryGroup(categoryGroup);
        rule.setPriority(priority);
        rule.setActive(true);
        return rule;
    }
}