package com.playdata.calen.familyalbum.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.familyalbum.domain.FamilyAlbum;
import com.playdata.calen.familyalbum.domain.FamilyAlbumItem;
import com.playdata.calen.familyalbum.domain.FamilyCategory;
import com.playdata.calen.familyalbum.domain.FamilyCategoryMember;
import com.playdata.calen.familyalbum.domain.FamilyMediaAsset;
import com.playdata.calen.familyalbum.domain.FamilyMediaType;
import com.playdata.calen.familyalbum.dto.FamilyAlbumBootstrapResponse;
import com.playdata.calen.familyalbum.dto.FamilyAlbumCreateRequest;
import com.playdata.calen.familyalbum.dto.FamilyAlbumResponse;
import com.playdata.calen.familyalbum.dto.FamilyCategoryCreateRequest;
import com.playdata.calen.familyalbum.dto.FamilyCategoryMemberResponse;
import com.playdata.calen.familyalbum.dto.FamilyCategoryResponse;
import com.playdata.calen.familyalbum.dto.FamilyMediaPageResponse;
import com.playdata.calen.familyalbum.dto.FamilyMediaResponse;
import com.playdata.calen.familyalbum.dto.FamilyUserSearchResponse;
import com.playdata.calen.familyalbum.repository.FamilyAlbumItemRepository;
import com.playdata.calen.familyalbum.repository.FamilyAlbumRepository;
import com.playdata.calen.familyalbum.repository.FamilyCategoryMemberRepository;
import com.playdata.calen.familyalbum.repository.FamilyCategoryRepository;
import com.playdata.calen.familyalbum.repository.FamilyMediaAssetRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FamilyAlbumService {

    private static final int DEFAULT_MEDIA_PAGE_SIZE = 10;
    private static final int MAX_MEDIA_PAGE_SIZE = 50;

    private final AppUserService appUserService;
    private final AppUserRepository appUserRepository;
    private final FamilyCategoryRepository familyCategoryRepository;
    private final FamilyCategoryMemberRepository familyCategoryMemberRepository;
    private final FamilyMediaAssetRepository familyMediaAssetRepository;
    private final FamilyAlbumRepository familyAlbumRepository;
    private final FamilyAlbumItemRepository familyAlbumItemRepository;
    private final FamilyMediaStorageService familyMediaStorageService;

    public FamilyAlbumBootstrapResponse getBootstrap(Long userId) {
        appUserService.getRequiredUser(userId);

        List<FamilyCategory> categories = familyCategoryRepository.findAccessibleCategories(userId);
        List<Long> categoryIds = categories.stream().map(FamilyCategory::getId).toList();
        List<FamilyCategoryMember> members = categoryIds.isEmpty()
                ? Collections.emptyList()
                : familyCategoryMemberRepository.findAllByCategoryIdInOrderByAddedAtAscIdAsc(categoryIds);
        List<FamilyAlbum> albums = categoryIds.isEmpty()
                ? Collections.emptyList()
                : familyAlbumRepository.findAllByCategoryIdInOrderByCreatedAtDescIdDesc(categoryIds);
        List<Long> albumIds = albums.stream().map(FamilyAlbum::getId).toList();

        Map<Long, List<FamilyCategoryMember>> membersByCategory = members.stream()
                .collect(Collectors.groupingBy(member -> member.getCategory().getId()));
        Map<Long, Long> mediaCountByCategory = categoryIds.isEmpty()
                ? Collections.emptyMap()
                : familyMediaAssetRepository.countAccessibleMediaByCategoryIds(categoryIds).stream()
                .collect(Collectors.toMap(
                        FamilyMediaAssetRepository.CategoryMediaCountView::getCategoryId,
                        FamilyMediaAssetRepository.CategoryMediaCountView::getMediaCount
                ));
        Map<Long, Integer> itemCountByAlbumId = albumIds.isEmpty()
                ? Collections.emptyMap()
                : familyAlbumItemRepository.countByAlbumIds(albumIds).stream()
                .collect(Collectors.toMap(
                        FamilyAlbumItemRepository.AlbumItemCountView::getAlbumId,
                        view -> Math.toIntExact(view.getItemCount())
                ));
        long totalPhotoCount = categoryIds.isEmpty()
                ? 0L
                : familyMediaAssetRepository.countByCategoryIdInAndMediaType(categoryIds, FamilyMediaType.PHOTO);
        long totalVideoCount = categoryIds.isEmpty()
                ? 0L
                : familyMediaAssetRepository.countByCategoryIdInAndMediaType(categoryIds, FamilyMediaType.VIDEO);

        return new FamilyAlbumBootstrapResponse(
                userId,
                List.of(),
                categories.stream()
                        .map(category -> toCategoryResponse(
                                category,
                                membersByCategory.getOrDefault(category.getId(), Collections.emptyList()),
                                mediaCountByCategory.getOrDefault(category.getId(), 0L)
                        ))
                        .toList(),
                totalPhotoCount,
                totalVideoCount,
                albums.stream()
                        .map(album -> toAlbumResponse(album, itemCountByAlbumId.getOrDefault(album.getId(), 0)))
                        .toList()
        );
    }

    public FamilyMediaPageResponse getCategoryMediaPage(Long userId, Long categoryId, Integer page, Integer size) {
        getAccessibleCategory(userId, categoryId);

        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        Page<FamilyMediaAsset> mediaPage = familyMediaAssetRepository.findPageByCategoryId(
                categoryId,
                PageRequest.of(normalizedPage, normalizedSize)
        );

        return new FamilyMediaPageResponse(
                mediaPage.getContent().stream().map(this::toMediaResponse).toList(),
                mediaPage.getNumber(),
                mediaPage.getSize(),
                mediaPage.getTotalElements(),
                mediaPage.getTotalPages()
        );
    }

    public FamilyMediaPageResponse getAlbumMediaPage(Long userId, Long albumId, Integer page, Integer size) {
        FamilyAlbum album = getAccessibleAlbum(userId, albumId);

        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        Page<FamilyAlbumItem> mediaPage = familyAlbumItemRepository.findAllByAlbumIdOrderByDisplayOrderAscIdAsc(
                album.getId(),
                PageRequest.of(normalizedPage, normalizedSize)
        );

        return new FamilyMediaPageResponse(
                mediaPage.getContent().stream()
                        .map(FamilyAlbumItem::getMedia)
                        .map(this::toMediaResponse)
                        .toList(),
                mediaPage.getNumber(),
                mediaPage.getSize(),
                mediaPage.getTotalElements(),
                mediaPage.getTotalPages()
        );
    }

    public List<FamilyUserSearchResponse> searchUsers(Long userId, String query) {
        appUserService.getRequiredUser(userId);

        String normalizedQuery = normalizeOptionalText(query, 80);
        if (!StringUtils.hasText(normalizedQuery) || normalizedQuery.length() < 2) {
            return List.of();
        }

        return appUserRepository.searchActiveUsersForInvitation(
                        userId,
                        normalizedQuery,
                        PageRequest.of(0, 10)
                ).stream()
                .map(this::toUserSearchResponse)
                .toList();
    }

    @Transactional
    public FamilyCategoryResponse createCategory(Long userId, FamilyCategoryCreateRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        FamilyCategory category = new FamilyCategory();
        category.setOwner(owner);
        category.setName(normalizeRequiredText(request.name(), "카테고리 이름은 필수입니다.", 120));
        category.setDescription(normalizeOptionalText(request.description(), 500));
        FamilyCategory savedCategory = familyCategoryRepository.save(category);

        Set<Long> memberUserIds = new LinkedHashSet<>();
        memberUserIds.add(userId);
        if (request.memberUserIds() != null) {
            request.memberUserIds().stream()
                    .filter(Objects::nonNull)
                    .forEach(memberUserIds::add);
        }

        Map<Long, AppUser> usersById = appUserRepository.findAllById(memberUserIds).stream()
                .filter(AppUser::isActive)
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));

        if (usersById.size() != memberUserIds.size()) {
            throw new BadRequestException("카테고리에 초대할 수 없는 사용자가 포함되어 있습니다.");
        }

        List<FamilyCategoryMember> members = memberUserIds.stream()
                .map(memberUserId -> {
                    FamilyCategoryMember member = new FamilyCategoryMember();
                    member.setCategory(savedCategory);
                    member.setUser(usersById.get(memberUserId));
                    return member;
                })
                .toList();

        familyCategoryMemberRepository.saveAll(members);
        return toCategoryResponse(savedCategory, members, 0L);
    }

    @Transactional
    public List<FamilyMediaResponse> uploadMedia(
            Long userId,
            Long categoryId,
            String caption,
            List<MultipartFile> files
    ) {
        AppUser owner = appUserService.getRequiredUser(userId);
        FamilyCategory category = getAccessibleCategory(userId, categoryId);
        String normalizedCaption = normalizeOptionalText(caption, 240);
        List<MultipartFile> selectedFiles = (files == null ? Collections.<MultipartFile>emptyList() : files).stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();

        if (selectedFiles.isEmpty()) {
            throw new BadRequestException("업로드할 사진이나 동영상을 선택해 주세요.");
        }

        LocalDateTime now = LocalDateTime.now();
        List<FamilyMediaAsset> savedAssets = selectedFiles.stream()
                .map(file -> createMediaAsset(owner, category, normalizedCaption, file, now))
                .map(familyMediaAssetRepository::save)
                .sorted(mediaComparator())
                .toList();

        return savedAssets.stream().map(this::toMediaResponse).toList();
    }

    @Transactional
    public FamilyAlbumResponse createAlbum(Long userId, FamilyAlbumCreateRequest request) {
        AppUser owner = appUserService.getRequiredUser(userId);
        FamilyCategory category = getAccessibleCategory(userId, request.categoryId());
        List<Long> mediaIds = distinctIds(request.mediaIds());
        if (mediaIds.isEmpty()) {
            throw new BadRequestException("앨범에 담을 사진이나 동영상을 하나 이상 선택해 주세요.");
        }

        List<FamilyMediaAsset> mediaAssets = familyMediaAssetRepository.findAllById(mediaIds);
        if (mediaAssets.size() != mediaIds.size()) {
            throw new NotFoundException("앨범에 담을 파일을 찾을 수 없습니다.");
        }

        boolean hasDifferentCategory = mediaAssets.stream()
                .anyMatch(asset -> !Objects.equals(asset.getCategory().getId(), category.getId()));
        if (hasDifferentCategory) {
            throw new BadRequestException("앨범에는 같은 카테고리의 파일만 담을 수 있습니다.");
        }

        FamilyAlbum album = new FamilyAlbum();
        album.setOwner(owner);
        album.setCategory(category);
        album.setTitle(normalizeRequiredText(request.title(), "앨범 이름을 입력해 주세요.", 120));
        album.setDescription(normalizeOptionalText(request.description(), 500));
        FamilyAlbum savedAlbum = familyAlbumRepository.save(album);

        Map<Long, FamilyMediaAsset> mediaById = mediaAssets.stream()
                .collect(Collectors.toMap(FamilyMediaAsset::getId, Function.identity()));

        List<FamilyAlbumItem> items = mediaIds.stream()
                .map(mediaById::get)
                .filter(Objects::nonNull)
                .map(media -> {
                    FamilyAlbumItem item = new FamilyAlbumItem();
                    item.setAlbum(savedAlbum);
                    item.setMedia(media);
                    item.setDisplayOrder(mediaIds.indexOf(media.getId()));
                    return item;
                })
                .toList();

        familyAlbumItemRepository.saveAll(items);
        return toAlbumResponse(savedAlbum, items.size());
    }

    public MediaDownload getMediaDownload(Long userId, Long mediaId) {
        appUserService.getRequiredUser(userId);
        FamilyMediaAsset media = getRequiredMedia(mediaId);
        verifyCategoryAccess(userId, media.getCategory());
        return new MediaDownload(
                media.getStoragePath(),
                familyMediaStorageService.loadAsResource(media.getStoragePath()),
                media.getOriginalFileName(),
                media.getContentType()
        );
    }

    private FamilyMediaAsset createMediaAsset(
            AppUser owner,
            FamilyCategory category,
            String caption,
            MultipartFile file,
            LocalDateTime now
    ) {
        FamilyMediaStorageService.StoredFamilyMedia stored = familyMediaStorageService.store(owner.getId(), category.getId(), file);
        FamilyMediaAsset asset = new FamilyMediaAsset();
        asset.setCategory(category);
        asset.setOwner(owner);
        asset.setMediaType(stored.mediaType());
        asset.setOriginalFileName(stored.originalFileName());
        asset.setStoredFileName(stored.storedFileName());
        asset.setStoragePath(stored.storagePath());
        asset.setContentType(stored.contentType());
        asset.setFileSize(stored.fileSize());
        asset.setCaption(caption);
        asset.setShared(true);
        asset.setUploadedAt(now);
        asset.setCapturedAt(now);
        return asset;
    }

    private FamilyCategory getAccessibleCategory(Long userId, Long categoryId) {
        FamilyCategory category = familyCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new NotFoundException("가족 카테고리를 찾을 수 없습니다."));
        verifyCategoryAccess(userId, category);
        return category;
    }

    private FamilyAlbum getAccessibleAlbum(Long userId, Long albumId) {
        FamilyAlbum album = familyAlbumRepository.findById(albumId)
                .orElseThrow(() -> new NotFoundException("?⑤쾾??李얠쓣 ???놁뒿?덈떎."));
        verifyCategoryAccess(userId, album.getCategory());
        return album;
    }

    private void verifyCategoryAccess(Long userId, FamilyCategory category) {
        if (Objects.equals(category.getOwner().getId(), userId)) {
            return;
        }
        if (familyCategoryMemberRepository.existsByCategoryIdAndUserId(category.getId(), userId)) {
            return;
        }
        throw new NotFoundException("가족 카테고리를 찾을 수 없습니다.");
    }

    private FamilyMediaAsset getRequiredMedia(Long mediaId) {
        return familyMediaAssetRepository.findById(mediaId)
                .orElseThrow(() -> new NotFoundException("사진 또는 동영상을 찾을 수 없습니다."));
    }

    private Comparator<FamilyMediaAsset> mediaComparator() {
        return Comparator
                .comparing(this::resolveMediaSortTime, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(FamilyMediaAsset::getUploadedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(FamilyMediaAsset::getId, Comparator.reverseOrder());
    }

    private LocalDateTime resolveMediaSortTime(FamilyMediaAsset asset) {
        return asset.getCapturedAt() != null ? asset.getCapturedAt() : asset.getUploadedAt();
    }

    private int normalizePage(Integer page) {
        return page == null ? 0 : Math.max(page, 0);
    }

    private int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_MEDIA_PAGE_SIZE;
        }
        return Math.max(1, Math.min(size, MAX_MEDIA_PAGE_SIZE));
    }

    private String normalizeRequiredText(String value, String message, int maxLength) {
        String normalized = normalizeOptionalText(value, maxLength);
        if (!StringUtils.hasText(normalized)) {
            throw new BadRequestException(message);
        }
        return normalized;
    }

    private String normalizeOptionalText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            return normalized.substring(0, maxLength);
        }
        return normalized;
    }

    private List<Long> distinctIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    }

    private FamilyUserSearchResponse toUserSearchResponse(AppUser user) {
        return new FamilyUserSearchResponse(
                user.getId(),
                user.getDisplayName(),
                maskLoginId(user.getLoginId())
        );
    }

    private String maskLoginId(String loginId) {
        if (!StringUtils.hasText(loginId)) {
            return "";
        }

        String normalized = loginId.trim();
        if (normalized.length() <= 2) {
            return normalized.charAt(0) + "*";
        }

        int visibleTailLength = Math.min(2, normalized.length() - 1);
        String head = normalized.substring(0, 1);
        String tail = normalized.substring(normalized.length() - visibleTailLength);
        String middle = "*".repeat(Math.max(1, normalized.length() - (1 + visibleTailLength)));
        return head + middle + tail;
    }

    private FamilyCategoryResponse toCategoryResponse(
            FamilyCategory category,
            List<FamilyCategoryMember> members,
            long mediaCount
    ) {
        List<FamilyCategoryMemberResponse> memberResponses = members.stream()
                .map(member -> new FamilyCategoryMemberResponse(
                        member.getUser().getId(),
                        member.getUser().getLoginId(),
                        member.getUser().getDisplayName(),
                        Objects.equals(member.getUser().getId(), category.getOwner().getId())
                ))
                .toList();

        return new FamilyCategoryResponse(
                category.getId(),
                category.getName(),
                category.getDescription(),
                category.getOwner().getId(),
                category.getOwner().getDisplayName(),
                category.getCreatedAt(),
                memberResponses.size(),
                mediaCount,
                memberResponses
        );
    }

    private FamilyMediaResponse toMediaResponse(FamilyMediaAsset asset) {
        return new FamilyMediaResponse(
                asset.getId(),
                asset.getCategory().getId(),
                asset.getCategory().getName(),
                asset.getOwner().getId(),
                asset.getOwner().getDisplayName(),
                asset.getMediaType().name(),
                asset.getOriginalFileName(),
                asset.getContentType(),
                asset.getFileSize(),
                asset.getCaption(),
                asset.isShared(),
                asset.getCapturedAt(),
                asset.getUploadedAt(),
                "/api/family-album/media/" + asset.getId() + "/content"
        );
    }

    private FamilyAlbumResponse toAlbumResponse(FamilyAlbum album, int itemCount) {
        return new FamilyAlbumResponse(
                album.getId(),
                album.getCategory().getId(),
                album.getCategory().getName(),
                album.getOwner().getId(),
                album.getOwner().getDisplayName(),
                album.getTitle(),
                album.getDescription(),
                album.getCreatedAt(),
                itemCount
        );
    }

    public record MediaDownload(
            String storagePath,
            Resource resource,
            String fileName,
            String contentType
    ) {
    }
}
