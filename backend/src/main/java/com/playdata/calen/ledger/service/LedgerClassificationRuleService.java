package com.playdata.calen.ledger.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerClassificationRule;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.dto.LedgerClassificationPreviewRequest;
import com.playdata.calen.ledger.dto.LedgerClassificationPreviewResponse;
import com.playdata.calen.ledger.dto.LedgerClassificationRuleRequest;
import com.playdata.calen.ledger.dto.LedgerClassificationRuleResponse;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerClassificationRuleRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerClassificationRuleService {

    private static final int DEFAULT_PRIORITY = 100;
    private static final int MIN_PRIORITY = 1;
    private static final int MAX_PRIORITY = 1000;

    private final AppUserService appUserService;
    private final LedgerClassificationRuleRepository ledgerClassificationRuleRepository;
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final PaymentMethodRepository paymentMethodRepository;

    public List<LedgerClassificationRuleResponse> getRules(Long userId, boolean includeInactive) {
        appUserService.getRequiredUser(userId);
        List<LedgerClassificationRule> rules = includeInactive
                ? ledgerClassificationRuleRepository.findAllByOwnerIdOrderByPriorityAscIdAsc(userId)
                : ledgerClassificationRuleRepository.findAllByOwnerIdAndActiveTrueOrderByPriorityAscIdAsc(userId);
        return rules.stream().map(this::toResponse).toList();
    }

    @Transactional
    public LedgerClassificationRuleResponse createRule(Long userId, LedgerClassificationRuleRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        LedgerClassificationRule rule = new LedgerClassificationRule();
        rule.setOwner(owner);
        apply(rule, userId, request);
        return toResponse(ledgerClassificationRuleRepository.save(rule));
    }

    @Transactional
    public LedgerClassificationRuleResponse updateRule(Long userId, Long ruleId, LedgerClassificationRuleRequest request) {
        appUserService.getRequiredUser(userId);
        LedgerClassificationRule rule = ledgerClassificationRuleRepository.findByIdAndOwnerId(ruleId, userId)
                .orElseThrow(() -> new NotFoundException("Classification rule was not found."));
        apply(rule, userId, request);
        return toResponse(rule);
    }

    @Transactional
    public LedgerClassificationRuleResponse deactivateRule(Long userId, Long ruleId) {
        LedgerClassificationRule rule = ledgerClassificationRuleRepository.findByIdAndOwnerId(ruleId, userId)
                .orElseThrow(() -> new NotFoundException("Classification rule was not found."));
        rule.setActive(false);
        return toResponse(rule);
    }

    public LedgerClassificationPreviewResponse preview(Long userId, LedgerClassificationPreviewRequest request) {
        appUserService.getRequiredUser(userId);
        String haystack = normalizeText(joinPreviewText(request));
        EntryType entryType = request == null ? null : request.entryType();
        if (haystack.isBlank()) {
            return new LedgerClassificationPreviewResponse(false, "No title or memo text was provided.", null);
        }

        return ledgerClassificationRuleRepository.findAllByOwnerIdAndActiveTrueOrderByPriorityAscIdAsc(userId).stream()
                .filter(rule -> rule.getEntryType() == null || entryType == null || rule.getEntryType() == entryType)
                .filter(rule -> haystack.contains(rule.getNormalizedKeyword()))
                .findFirst()
                .map(rule -> new LedgerClassificationPreviewResponse(
                        true,
                        "Matched keyword: " + rule.getKeyword(),
                        toResponse(rule)
                ))
                .orElseGet(() -> new LedgerClassificationPreviewResponse(false, "No classification rule matched.", null));
    }

    private void apply(LedgerClassificationRule rule, Long userId, LedgerClassificationRuleRequest request) {
        String keyword = normalizeRequiredKeyword(request.keyword());
        CategoryGroup categoryGroup = categoryGroupRepository.findByIdAndOwnerId(request.categoryGroupId(), userId)
                .filter(CategoryGroup::isActive)
                .orElseThrow(() -> new BadRequestException("Category group is not active or was not found."));
        CategoryDetail categoryDetail = null;
        if (request.categoryDetailId() != null) {
            categoryDetail = categoryDetailRepository.findByIdAndGroupOwnerId(request.categoryDetailId(), userId)
                    .filter(CategoryDetail::isActive)
                    .orElseThrow(() -> new BadRequestException("Category detail is not active or was not found."));
            if (!categoryDetail.getGroup().getId().equals(categoryGroup.getId())) {
                throw new BadRequestException("Category detail must belong to the selected category group.");
            }
        }
        PaymentMethod paymentMethod = null;
        if (request.paymentMethodId() != null) {
            paymentMethod = paymentMethodRepository.findByIdAndOwnerId(request.paymentMethodId(), userId)
                    .filter(PaymentMethod::isActive)
                    .orElseThrow(() -> new BadRequestException("Payment method is not active or was not found."));
        }
        EntryType entryType = request.entryType() != null ? request.entryType() : categoryGroup.getEntryType();
        if (entryType != categoryGroup.getEntryType()) {
            throw new BadRequestException("Rule entry type must match the selected category group.");
        }

        rule.setKeyword(keyword);
        rule.setNormalizedKeyword(normalizeText(keyword));
        rule.setEntryType(entryType);
        rule.setCategoryGroup(categoryGroup);
        rule.setCategoryDetail(categoryDetail);
        rule.setPaymentMethod(paymentMethod);
        rule.setPriority(normalizePriority(request.priority()));
        rule.setActive(request.active() == null || request.active());
    }

    private LedgerClassificationRuleResponse toResponse(LedgerClassificationRule rule) {
        CategoryGroup categoryGroup = rule.getCategoryGroup();
        CategoryDetail categoryDetail = rule.getCategoryDetail();
        PaymentMethod paymentMethod = rule.getPaymentMethod();
        return new LedgerClassificationRuleResponse(
                rule.getId(),
                rule.getKeyword(),
                rule.getNormalizedKeyword(),
                rule.getEntryType(),
                categoryGroup == null ? null : categoryGroup.getId(),
                categoryGroup == null ? null : categoryGroup.getName(),
                categoryDetail == null ? null : categoryDetail.getId(),
                categoryDetail == null ? null : categoryDetail.getName(),
                paymentMethod == null ? null : paymentMethod.getId(),
                paymentMethod == null ? null : paymentMethod.getName(),
                rule.getPriority(),
                rule.isActive(),
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }

    private String joinPreviewText(LedgerClassificationPreviewRequest request) {
        if (request == null) {
            return "";
        }
        String title = request.title() == null ? "" : request.title();
        String memo = request.memo() == null ? "" : request.memo();
        return title + " " + memo;
    }
    private String normalizeRequiredKeyword(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.isBlank()) {
            throw new BadRequestException("Rule keyword is required.");
        }
        if (normalized.length() > 160) {
            throw new BadRequestException("Rule keyword must be 160 characters or fewer.");
        }
        return normalized;
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private int normalizePriority(Integer priority) {
        int value = priority == null ? DEFAULT_PRIORITY : priority;
        if (value < MIN_PRIORITY || value > MAX_PRIORITY) {
            throw new BadRequestException("Rule priority must be between 1 and 1000.");
        }
        return value;
    }
}