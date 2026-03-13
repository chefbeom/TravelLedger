package com.playdata.calen.ledger.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.dto.CategoryDetailRequest;
import com.playdata.calen.ledger.dto.CategoryDetailResponse;
import com.playdata.calen.ledger.dto.CategoryGroupRequest;
import com.playdata.calen.ledger.dto.CategoryGroupResponse;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final AppUserService appUserService;
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;

    public List<CategoryGroupResponse> getCategories(Long userId, EntryType entryType) {
        AppUser owner = appUserService.getRequiredUser(userId);
        List<CategoryGroup> groups = entryType == null
                ? categoryGroupRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId())
                : categoryGroupRepository.findAllByOwnerIdAndEntryTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId(), entryType);

        return groups.stream()
                .map(this::toGroupResponse)
                .toList();
    }

    @Transactional
    public CategoryGroupResponse createGroup(Long userId, CategoryGroupRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        CategoryGroup group = new CategoryGroup();
        group.setOwner(owner);
        group.setName(request.name());
        group.setEntryType(request.entryType());
        group.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
        group.setActive(true);
        return toGroupResponse(categoryGroupRepository.save(group));
    }

    @Transactional
    public CategoryDetailResponse createDetail(Long userId, CategoryDetailRequest request) {
        CategoryGroup group = categoryGroupRepository.findByIdAndOwnerId(request.groupId(), userId)
                .orElseThrow(() -> new NotFoundException("대분류를 찾을 수 없습니다."));

        CategoryDetail detail = new CategoryDetail();
        detail.setGroup(group);
        detail.setName(request.name());
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
    public void deactivateDetail(Long userId, Long id) {
        CategoryDetail detail = categoryDetailRepository.findByIdAndGroupOwnerId(id, userId)
                .orElseThrow(() -> new NotFoundException("소분류를 찾을 수 없습니다."));
        detail.setActive(false);
    }

    private CategoryGroupResponse toGroupResponse(CategoryGroup group) {
        List<CategoryDetailResponse> details = group.getDetails().stream()
                .filter(CategoryDetail::isActive)
                .sorted(Comparator.comparing(CategoryDetail::getDisplayOrder).thenComparing(CategoryDetail::getId))
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
}
