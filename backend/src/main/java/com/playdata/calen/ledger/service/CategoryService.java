package com.playdata.calen.ledger.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.dto.CategoryDetailRequest;
import com.playdata.calen.ledger.dto.CategoryDetailResponse;
import com.playdata.calen.ledger.dto.CategoryGroupRequest;
import com.playdata.calen.ledger.dto.CategoryGroupResponse;
import com.playdata.calen.ledger.dto.LedgerClassificationDeleteRequest;
import com.playdata.calen.ledger.dto.LedgerClassificationUsageEntryResponse;
import com.playdata.calen.ledger.dto.LedgerClassificationUsageResponse;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private static final String UNCATEGORIZED_NAME = "미분류";
    private static final int USAGE_PREVIEW_SIZE = 30;

    private final AppUserService appUserService;
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public List<CategoryGroupResponse> getCategories(Long userId, EntryType entryType) {
        return getCategories(userId, entryType, false);
    }

    public List<CategoryGroupResponse> getCategories(Long userId, EntryType entryType, boolean includeInactive) {
        AppUser owner = appUserService.getRequiredUser(userId);
        List<CategoryGroup> groups;
        if (includeInactive) {
            groups = entryType == null
                    ? categoryGroupRepository.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(owner.getId())
                    : categoryGroupRepository.findAllByOwnerIdAndEntryTypeOrderByDisplayOrderAscIdAsc(owner.getId(), entryType);
        } else {
            groups = entryType == null
                    ? categoryGroupRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId())
                    : categoryGroupRepository.findAllByOwnerIdAndEntryTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId(), entryType);
        }

        return groups.stream()
                .sorted(Comparator.comparing(CategoryGroup::isActive).reversed()
                        .thenComparing(CategoryGroup::getDisplayOrder)
                        .thenComparing(CategoryGroup::getId))
                .map(group -> toGroupResponse(group, includeInactive))
                .toList();
    }

    @Transactional
    public CategoryGroupResponse createGroup(Long userId, CategoryGroupRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        String name = normalizeName(request.name());
        if (categoryGroupRepository.findFirstByOwnerIdAndEntryTypeAndNameIgnoreCaseOrderByIdAsc(owner.getId(), request.entryType(), name).isPresent()) {
            throw new BadRequestException("이미 있는 분류입니다.");
        }
        CategoryGroup group = new CategoryGroup();
        group.setOwner(owner);
        group.setName(name);
        group.setEntryType(request.entryType());
        group.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
        group.setActive(true);
        return toGroupResponse(categoryGroupRepository.save(group));
    }

    @Transactional
    public CategoryDetailResponse createDetail(Long userId, CategoryDetailRequest request) {
        CategoryGroup group = categoryGroupRepository.findByIdAndOwnerId(request.groupId(), userId)
                .orElseThrow(() -> new NotFoundException("대분류를 찾을 수 없습니다."));

        String name = normalizeName(request.name());
        if (categoryDetailRepository.findFirstByGroupIdAndNameIgnoreCaseOrderByIdAsc(group.getId(), name).isPresent()) {
            throw new BadRequestException("이미 있는 분류입니다.");
        }
        CategoryDetail detail = new CategoryDetail();
        detail.setGroup(group);
        detail.setName(name);
        detail.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
        detail.setActive(true);
        return toDetailResponse(categoryDetailRepository.save(detail));
    }

    @Transactional
    public void deactivateGroup(Long userId, Long id) {
        CategoryGroup group = categoryGroupRepository.findByIdAndOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("대분류를 찾을 수 없습니다."));
        group.setActive(false);
        group.getDetails().forEach(detail -> detail.setActive(false));
    }

    @Transactional
    public CategoryGroupResponse activateGroup(Long userId, Long id) {
        CategoryGroup group = categoryGroupRepository.findByIdAndOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("대분류를 찾을 수 없습니다."));
        group.setActive(true);
        group.getDetails().forEach(detail -> detail.setActive(true));
        return toGroupResponse(group, true);
    }

    @Transactional
    public void deactivateDetail(Long userId, Long id) {
        CategoryDetail detail = categoryDetailRepository.findByIdAndGroupOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("소분류를 찾을 수 없습니다."));
        detail.setActive(false);
    }

    @Transactional
    public CategoryDetailResponse activateDetail(Long userId, Long id) {
        CategoryDetail detail = categoryDetailRepository.findByIdAndGroupOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("소분류를 찾을 수 없습니다."));
        detail.getGroup().setActive(true);
        detail.setActive(true);
        return toDetailResponse(detail);
    }

    public LedgerClassificationUsageResponse getGroupUsage(Long userId, Long id) {
        CategoryGroup group = categoryGroupRepository.findByIdAndOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("대분류를 찾을 수 없습니다."));
        Page<LedgerEntry> page = ledgerEntryRepository.findAllByOwnerIdAndCategoryGroupIdOrderByEntryDateDescIdDesc(
                userId,
                id,
                PageRequest.of(0, USAGE_PREVIEW_SIZE)
        );
        long totalCount = ledgerEntryRepository.countByOwnerIdAndCategoryGroupId(userId, id);
        return toUsageResponse("CATEGORY_GROUP", group.getId(), group.getName(), totalCount, page);
    }

    public LedgerClassificationUsageResponse getDetailUsage(Long userId, Long id) {
        CategoryDetail detail = categoryDetailRepository.findByIdAndGroupOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("소분류를 찾을 수 없습니다."));
        Page<LedgerEntry> page = ledgerEntryRepository.findAllByOwnerIdAndCategoryDetailIdOrderByEntryDateDescIdDesc(
                userId,
                id,
                PageRequest.of(0, USAGE_PREVIEW_SIZE)
        );
        long totalCount = ledgerEntryRepository.countByOwnerIdAndCategoryDetailId(userId, id);
        return toUsageResponse("CATEGORY_DETAIL", detail.getId(), detail.getName(), totalCount, page);
    }

    @Transactional
    public void deleteGroup(Long userId, Long id, LedgerClassificationDeleteRequest request) {
        LedgerClassificationDeleteRequest deleteRequest = normalizeDeleteRequest(request);
        CategoryGroup group = categoryGroupRepository.findByIdAndOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("대분류를 찾을 수 없습니다."));
        CategoryGroup replacementGroup = resolveReplacementGroup(userId, group.getEntryType(), id, deleteRequest.replacementCategoryGroupId());
        CategoryDetail replacementDetail = resolveReplacementDetail(userId, replacementGroup, deleteRequest.replacementCategoryDetailId());

        ledgerEntryRepository.findAllByOwnerIdAndCategoryGroupId(userId, id).forEach(entry -> {
            entry.setCategoryGroup(replacementGroup);
            entry.setCategoryDetail(replacementDetail);
        });

        categoryDetailRepository.deleteAll(group.getDetails());
        categoryGroupRepository.delete(group);
    }

    @Transactional
    public void deleteDetail(Long userId, Long id, LedgerClassificationDeleteRequest request) {
        LedgerClassificationDeleteRequest deleteRequest = normalizeDeleteRequest(request);
        CategoryDetail detail = categoryDetailRepository.findByIdAndGroupOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("소분류를 찾을 수 없습니다."));
        CategoryDetail replacementDetail = resolveSameGroupReplacementDetail(userId, detail, deleteRequest.replacementCategoryDetailId());

        ledgerEntryRepository.findAllByOwnerIdAndCategoryDetailId(userId, id)
                .forEach(entry -> entry.setCategoryDetail(replacementDetail));

        categoryDetailRepository.delete(detail);
    }

    private CategoryGroupResponse toGroupResponse(CategoryGroup group) {
        return toGroupResponse(group, false);
    }

    private CategoryGroupResponse toGroupResponse(CategoryGroup group, boolean includeInactive) {
        List<CategoryDetailResponse> details = group.getDetails().stream()
                .filter(detail -> includeInactive || detail.isActive())
                .sorted(Comparator.comparing(CategoryDetail::isActive).reversed()
                        .thenComparing(CategoryDetail::getDisplayOrder)
                        .thenComparing(CategoryDetail::getId))
                .map(this::toDetailResponse)
                .toList();

        return new CategoryGroupResponse(
                group.getId(),
                group.getName(),
                group.getEntryType(),
                group.getDisplayOrder(),
                group.isActive(),
                details
        );
    }

    private CategoryDetailResponse toDetailResponse(CategoryDetail detail) {
        return new CategoryDetailResponse(
                detail.getId(),
                detail.getGroup().getId(),
                detail.getName(),
                detail.getDisplayOrder(),
                detail.isActive()
        );
    }

    private LedgerClassificationUsageResponse toUsageResponse(
            String targetType,
            Long targetId,
            String targetName,
            long totalCount,
            Page<LedgerEntry> page
    ) {
        return new LedgerClassificationUsageResponse(
                targetType,
                targetId,
                targetName,
                totalCount,
                totalCount > page.getNumberOfElements(),
                page.getContent().stream()
                        .map(this::toUsageEntryResponse)
                        .toList()
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

    private CategoryGroup resolveReplacementGroup(Long userId, EntryType entryType, Long deletedGroupId, Long replacementGroupId) {
        if (replacementGroupId != null) {
            if (replacementGroupId.equals(deletedGroupId)) {
                throw new BadRequestException("삭제할 대분류는 대체 분류로 사용할 수 없습니다.");
            }
            CategoryGroup replacementGroup = categoryGroupRepository.findByIdAndOwnerId(replacementGroupId, userId)
                    .orElseThrow(() -> new NotFoundException("대체 대분류를 찾을 수 없습니다."));
            if (!replacementGroup.getEntryType().equals(entryType)) {
                throw new BadRequestException("대체 대분류의 수입/지출 구분이 삭제 대상과 일치하지 않습니다.");
            }
            replacementGroup.setActive(true);
            return replacementGroup;
        }
        return resolveUncategorizedGroup(userId, entryType, deletedGroupId);
    }

    private CategoryDetail resolveReplacementDetail(Long userId, CategoryGroup replacementGroup, Long replacementDetailId) {
        if (replacementDetailId == null) {
            return null;
        }
        CategoryDetail replacementDetail = categoryDetailRepository.findByIdAndGroupOwnerId(replacementDetailId, userId)
                .orElseThrow(() -> new NotFoundException("대체 소분류를 찾을 수 없습니다."));
        if (!replacementDetail.getGroup().getId().equals(replacementGroup.getId())) {
            throw new BadRequestException("대체 소분류가 선택한 대분류에 속하지 않습니다.");
        }
        replacementDetail.setActive(true);
        return replacementDetail;
    }

    private CategoryDetail resolveSameGroupReplacementDetail(Long userId, CategoryDetail deletedDetail, Long replacementDetailId) {
        if (replacementDetailId == null) {
            return null;
        }
        if (replacementDetailId.equals(deletedDetail.getId())) {
            throw new BadRequestException("삭제할 소분류는 대체 소분류로 사용할 수 없습니다.");
        }
        CategoryDetail replacementDetail = categoryDetailRepository.findByIdAndGroupOwnerId(replacementDetailId, userId)
                .orElseThrow(() -> new NotFoundException("대체 소분류를 찾을 수 없습니다."));
        if (!replacementDetail.getGroup().getId().equals(deletedDetail.getGroup().getId())) {
            throw new BadRequestException("대체 소분류는 같은 대분류 안에서 선택해 주세요.");
        }
        replacementDetail.setActive(true);
        return replacementDetail;
    }

    private CategoryGroup resolveUncategorizedGroup(Long userId, EntryType entryType, Long excludedGroupId) {
        return categoryGroupRepository.findAllByOwnerIdAndEntryTypeOrderByDisplayOrderAscIdAsc(userId, entryType).stream()
                .filter(group -> !group.getId().equals(excludedGroupId))
                .filter(group -> UNCATEGORIZED_NAME.equalsIgnoreCase(group.getName()))
                .findFirst()
                .map(group -> {
                    group.setActive(true);
                    return group;
                })
                .orElseGet(() -> createUncategorizedGroup(userId, entryType));
    }

    private CategoryGroup createUncategorizedGroup(Long userId, EntryType entryType) {
        AppUser owner = appUserService.getRequiredUser(userId);
        CategoryGroup group = new CategoryGroup();
        group.setOwner(owner);
        group.setName(UNCATEGORIZED_NAME);
        group.setEntryType(entryType);
        group.setDisplayOrder(Integer.MAX_VALUE);
        group.setActive(true);
        return categoryGroupRepository.save(group);
    }

    private LedgerClassificationDeleteRequest normalizeDeleteRequest(LedgerClassificationDeleteRequest request) {
        return request == null ? new LedgerClassificationDeleteRequest(null, null, null) : request;
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }
}
