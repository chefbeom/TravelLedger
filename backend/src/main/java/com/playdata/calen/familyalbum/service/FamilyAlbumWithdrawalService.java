package com.playdata.calen.familyalbum.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.familyalbum.domain.FamilyAlbum;
import com.playdata.calen.familyalbum.domain.FamilyCategory;
import com.playdata.calen.familyalbum.domain.FamilyCategoryMember;
import com.playdata.calen.familyalbum.domain.FamilyMediaAsset;
import com.playdata.calen.familyalbum.repository.FamilyAlbumRepository;
import com.playdata.calen.familyalbum.repository.FamilyAlbumItemRepository;
import com.playdata.calen.familyalbum.repository.FamilyCategoryMemberRepository;
import com.playdata.calen.familyalbum.repository.FamilyCategoryRepository;
import com.playdata.calen.familyalbum.repository.FamilyMediaAssetRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ownership transfer is only a step of withdrawal, not an account deletion operation. */
@Service
@RequiredArgsConstructor
public class FamilyAlbumWithdrawalService {
    private final FamilyCategoryRepository categories;
    private final FamilyCategoryMemberRepository members;
    private final FamilyAlbumRepository albums;
    private final FamilyAlbumItemRepository albumItems;
    private final FamilyMediaAssetRepository media;
    private final AppUserRepository users;

    @Transactional(readOnly = true)
    public List<TransferChoice> requiredTransfers(Long userId) {
        List<FamilyCategory> owned = categories.findAllByOwnerIdOrderByIdAsc(userId);
        List<FamilyCategoryMember> categoryMembers = owned.isEmpty() ? List.of()
                : members.findAllByCategoryIdInOrderByAddedAtAscIdAsc(owned.stream().map(FamilyCategory::getId).toList());
        return buildChoices(userId, owned, categoryMembers);
    }

    /**
     * Run only inside the withdrawal transaction after locking the withdrawing user.
     * Shared media owned by the departing user is transferred without modifying storage objects.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transferForWithdrawal(Long userId, Map<Long, Long> selectedOwners) {
        List<Long> relatedIds = categories.findRelatedCategoryIdsForWithdrawal(userId);
        List<FamilyCategory> relatedCategories = relatedIds.isEmpty() ? List.of()
                : categories.lockCategoriesForWithdrawal(relatedIds);
        List<FamilyCategory> ownedCategories = relatedCategories.stream()
                .filter(category -> Objects.equals(category.getOwner().getId(), userId)).toList();
        List<FamilyCategoryMember> categoryMembers = ownedCategories.isEmpty() ? List.of()
                : members.lockForWithdrawal(ownedCategories.stream().map(FamilyCategory::getId).toList());
        List<TransferChoice> required = buildChoices(userId, ownedCategories, categoryMembers);
        Map<Long, Long> selections = selectedOwners == null ? Map.of() : new java.util.HashMap<>(selectedOwners);
        if (selections.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw new BadRequestException("앨범 소유권을 이전할 멤버를 선택해 주세요.");
        }
        Set<Long> requiredIds = required.stream().map(TransferChoice::categoryId).collect(Collectors.toSet());
        if (!selections.keySet().equals(requiredIds)) {
            throw new BadRequestException("공유 가족 앨범마다 소유권을 이전할 멤버를 선택해 주세요.");
        }

        Map<Long, FamilyCategory> byId = ownedCategories.stream()
                .collect(Collectors.toMap(FamilyCategory::getId, Function.identity()));
        Map<Long, AppUser> newOwners = new java.util.HashMap<>();
        for (TransferChoice choice : required) {
            Long selected = selections.get(choice.categoryId());
            AppUser owner = categoryMembers.stream()
                    .filter(member -> Objects.equals(member.getCategory().getId(), choice.categoryId()))
                    .map(FamilyCategoryMember::getUser)
                    .filter(member -> Objects.equals(member.getId(), selected) && member.isActive()
                            && !Objects.equals(member.getId(), userId))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException("앨범 소유권을 이전할 수 있는 활성 멤버를 선택해 주세요."));
            newOwners.put(choice.categoryId(), owner);
        }

        // Albums created in someone else's category belong to that category after withdrawal.
        // If its owner cannot take responsibility, do not change any ownership.
        List<FamilyAlbum> ownedAlbums = albums.findAllByOwnerId(userId);
        Set<Long> referencedSharedMedia = new java.util.HashSet<>();
        for (var item : albumItems.lockOwnedMediaReferencesForWithdrawal(userId)) {
            FamilyCategory category = item.getAlbum().getCategory();
            if (!Objects.equals(category.getId(), item.getMedia().getCategory().getId())) {
                throw new BadRequestException("다른 카테고리를 참조하는 앨범 사진이 있어 탈퇴를 진행할 수 없습니다.");
            }
            if (!Objects.equals(category.getOwner().getId(), userId) || requiredIds.contains(category.getId())
                    || !Objects.equals(item.getAlbum().getOwner().getId(), userId)) {
                referencedSharedMedia.add(item.getMedia().getId());
            }
        }
        List<FamilyMediaAsset> sharedMedia = media.findAllByOwnerIdOrderByUploadedAtDescIdDesc(userId).stream()
                .filter(asset -> asset.isShared() || referencedSharedMedia.contains(asset.getId())).toList();
        Set<Long> targetUserIds = new java.util.TreeSet<>();
        newOwners.values().forEach(owner -> targetUserIds.add(owner.getId()));
        for (FamilyAlbum album : ownedAlbums) {
            FamilyCategory category = album.getCategory();
            if (!Objects.equals(category.getOwner().getId(), userId) && !category.getOwner().isActive()) {
                throw new BadRequestException("소유자가 비활성인 공유 앨범이 있어 소유권을 이전할 수 없습니다.");
            }
            if (!Objects.equals(category.getOwner().getId(), userId)) {
                targetUserIds.add(category.getOwner().getId());
            }
        }
        for (FamilyMediaAsset asset : sharedMedia) {
            FamilyCategory category = asset.getCategory();
            if (!Objects.equals(category.getOwner().getId(), userId) && !category.getOwner().isActive()) {
                throw new BadRequestException("소유자가 비활성인 공유 사진이 있어 소유권을 이전할 수 없습니다.");
            }
            if (!Objects.equals(category.getOwner().getId(), userId)) {
                targetUserIds.add(category.getOwner().getId());
            }
        }
        for (Long targetUserId : targetUserIds) {
            users.findActiveForPreferenceUpdate(targetUserId)
                    .orElseThrow(() -> new BadRequestException("이전할 앨범의 소유자가 더 이상 활성 상태가 아닙니다."));
        }
        newOwners.forEach((id, owner) -> byId.get(id).setOwner(owner));
        for (FamilyAlbum album : ownedAlbums) {
            AppUser newOwner = newOwners.get(album.getCategory().getId());
            if (newOwner != null) {
                album.setOwner(newOwner);
            } else if (!Objects.equals(album.getCategory().getOwner().getId(), userId)) {
                album.setOwner(album.getCategory().getOwner());
            }
        }
        for (FamilyMediaAsset asset : sharedMedia) {
            AppUser newOwner = newOwners.get(asset.getCategory().getId());
            if (newOwner != null) {
                asset.setOwner(newOwner);
            } else if (!Objects.equals(asset.getCategory().getOwner().getId(), userId)) {
                asset.setOwner(asset.getCategory().getOwner());
            }
        }
    }

    private List<TransferChoice> buildChoices(Long userId, List<FamilyCategory> owned, List<FamilyCategoryMember> categoryMembers) {
        if (owned.isEmpty()) return List.of();
        Map<Long, List<FamilyCategoryMember>> byCategory = categoryMembers.stream()
                .collect(Collectors.groupingBy(member -> member.getCategory().getId()));
        return owned.stream().filter(category -> {
            boolean hasOtherMember = byCategory.getOrDefault(category.getId(), List.of()).stream()
                    .anyMatch(member -> !Objects.equals(member.getUser().getId(), userId));
            return hasOtherMember || media.existsByCategoryIdAndOwnerIdNot(category.getId(), userId)
                    || albums.existsByCategoryIdAndOwnerIdNot(category.getId(), userId);
        }).map(category -> new TransferChoice(category.getId(), category.getName(),
                byCategory.getOrDefault(category.getId(), List.of()).stream()
                        .map(FamilyCategoryMember::getUser)
                        .filter(user -> user.isActive() && !Objects.equals(user.getId(), userId))
                        .map(user -> new EligibleMember(user.getId(), user.getDisplayName(), user.getLoginId()))
                        .toList())).toList();
    }

    public record TransferChoice(Long categoryId, String categoryName, List<EligibleMember> eligibleMembers) {}
    public record EligibleMember(Long userId, String displayName, String loginId) {}
}
