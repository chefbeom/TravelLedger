package com.playdata.calen.familyalbum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.familyalbum.domain.FamilyAlbum;
import com.playdata.calen.familyalbum.domain.FamilyAlbumItem;
import com.playdata.calen.familyalbum.domain.FamilyCategory;
import com.playdata.calen.familyalbum.domain.FamilyCategoryMember;
import com.playdata.calen.familyalbum.domain.FamilyMediaAsset;
import com.playdata.calen.familyalbum.repository.FamilyAlbumRepository;
import com.playdata.calen.familyalbum.repository.FamilyAlbumItemRepository;
import com.playdata.calen.familyalbum.repository.FamilyCategoryMemberRepository;
import com.playdata.calen.familyalbum.repository.FamilyCategoryRepository;
import com.playdata.calen.familyalbum.repository.FamilyMediaAssetRepository;
import com.playdata.calen.familyalbum.service.FamilyAlbumWithdrawalService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "app.seed.enabled=true",
        "app.seed.allow-insecure-default-credentials=true",
        "spring.datasource.url=jdbc:h2:mem:family-withdrawal-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@Transactional
class FamilyAlbumWithdrawalServiceTest {
    @Autowired private FamilyAlbumWithdrawalService service;
    @Autowired private AppUserRepository users;
    @Autowired private FamilyCategoryRepository categories;
    @Autowired private FamilyCategoryMemberRepository members;
    @Autowired private FamilyAlbumRepository albums;
    @Autowired private FamilyAlbumItemRepository items;
    @Autowired private FamilyMediaAssetRepository media;

    @Test
    void transferPreservesAnotherMembersMediaAndTransfersTheOwnersAlbum() {
        AppUser departing = user("hana");
        AppUser member = user("minsu");
        FamilyCategory category = category(departing);
        addMember(category, departing);
        addMember(category, member);
        FamilyAlbum album = album(category, departing);
        FamilyMediaAsset photo = photo(category, member);

        var choices = service.requiredTransfers(departing.getId());
        assertThat(choices).hasSize(1);
        assertThat(choices.get(0).eligibleMembers()).extracting("userId").containsExactly(member.getId());
        service.transferForWithdrawal(departing.getId(), Map.of(category.getId(), member.getId()));
        categories.flush();
        albums.flush();

        assertThat(category.getOwner().getId()).isEqualTo(member.getId());
        assertThat(album.getOwner().getId()).isEqualTo(member.getId());
        assertThat(photo.getOwner().getId()).isEqualTo(member.getId());
        assertThat(photo.getStoragePath()).isEqualTo("other-user/photo.jpg");
        assertThat(media.findById(photo.getId())).isPresent();
    }

    @Test
    void sharedPhotosUploadedByDepartingOwnerTransferButOtherUsersAndPrivatePhotosDoNot() {
        AppUser departing = user("hana");
        AppUser newOwner = user("minsu");
        AppUser otherMember = user("admin");
        FamilyCategory category = category(departing);
        addMember(category, newOwner); addMember(category, otherMember);
        FamilyMediaAsset ownShared = photo(category, departing);
        FamilyMediaAsset ownPrivate = photo(category, departing); ownPrivate.setShared(false);
        FamilyMediaAsset others = photo(category, otherMember);
        String originalPath = ownShared.getStoragePath();

        service.transferForWithdrawal(departing.getId(), Map.of(category.getId(), newOwner.getId()));
        media.flush();

        assertThat(ownShared.getOwner().getId()).isEqualTo(newOwner.getId());
        assertThat(ownShared.getStoragePath()).isEqualTo(originalPath);
        assertThat(ownPrivate.getOwner().getId()).isEqualTo(departing.getId());
        assertThat(others.getOwner().getId()).isEqualTo(otherMember.getId());
    }

    @Test
    void sharedPhotoInAnotherPersonsCategoryTransfersToItsActiveOwner() {
        AppUser departing = user("hana");
        AppUser categoryOwner = user("minsu");
        FamilyCategory category = category(categoryOwner);
        FamilyMediaAsset photo = photo(category, departing);
        String originalPath = photo.getStoragePath();
        service.transferForWithdrawal(departing.getId(), Map.of());
        media.flush();
        assertThat(photo.getOwner().getId()).isEqualTo(categoryOwner.getId());
        assertThat(photo.getStoragePath()).isEqualTo(originalPath);
    }

    @Test
    void aPrivateFlaggedPhotoAlreadyInASharedAlbumIsPreservedWithThatAlbum() {
        AppUser departing = user("hana"), member = user("minsu");
        FamilyCategory category = category(departing);
        addMember(category, member);
        FamilyAlbum album = album(category, departing);
        FamilyMediaAsset photo = photo(category, departing); photo.setShared(false);
        addItem(album, photo);

        service.transferForWithdrawal(departing.getId(), Map.of(category.getId(), member.getId()));
        media.flush(); albums.flush();
        assertThat(album.getOwner().getId()).isEqualTo(member.getId());
        assertThat(photo.getOwner().getId()).isEqualTo(member.getId());
        assertThat(photo.getStoragePath()).isEqualTo("other-user/photo.jpg");
        assertThat(items.findAllByAlbumIdInOrderByDisplayOrderAscIdAsc(java.util.List.of(album.getId()))).hasSize(1);
    }

    @Test
    void aPrivateFlaggedPhotoInAnotherOwnersAlbumTransfersToTheCategoryOwner() {
        AppUser departing = user("hana"), categoryOwner = user("minsu");
        FamilyCategory category = category(categoryOwner);
        FamilyAlbum album = album(category, categoryOwner);
        FamilyMediaAsset photo = photo(category, departing); photo.setShared(false);
        addItem(album, photo);
        service.transferForWithdrawal(departing.getId(), Map.of());
        assertThat(photo.getOwner().getId()).isEqualTo(categoryOwner.getId());
    }

    @Test
    void inconsistentAlbumAndPhotoCategoriesStopTransfer() {
        AppUser departing = user("hana"), categoryOwner = user("minsu");
        FamilyCategory first = category(categoryOwner), second = category(categoryOwner);
        FamilyAlbum album = album(first, categoryOwner);
        FamilyMediaAsset photo = photo(second, departing); photo.setShared(false);
        addItem(album, photo);
        assertThatThrownBy(() -> service.transferForWithdrawal(departing.getId(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("카테고리");
        assertThat(photo.getOwner().getId()).isEqualTo(departing.getId());
    }

    @Test
    void sharedPhotoCannotTransferToAnInactiveCategoryOwner() {
        AppUser departing = user("hana");
        AppUser categoryOwner = user("minsu");
        FamilyCategory category = category(categoryOwner);
        FamilyMediaAsset photo = photo(category, departing);
        categoryOwner.setActive(false); users.saveAndFlush(categoryOwner);
        assertThatThrownBy(() -> service.transferForWithdrawal(departing.getId(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("비활성");
        assertThat(photo.getOwner().getId()).isEqualTo(departing.getId());
    }

    @Test
    void cannotTransferToANonMemberOrSkipASharedCategory() {
        AppUser departing = user("hana");
        FamilyCategory category = category(departing);
        addMember(category, departing);
        addMember(category, user("minsu"));
        AppUser outsider = user("admin");
        assertThatThrownBy(() -> service.transferForWithdrawal(departing.getId(), Map.of()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.transferForWithdrawal(departing.getId(), Map.of(category.getId(), outsider.getId())))
                .isInstanceOf(BadRequestException.class);
        assertThat(category.getOwner().getId()).isEqualTo(departing.getId());
    }

    @Test
    void anInvalidSelectionDoesNotPartiallyTransferOtherCategories() {
        AppUser departing = user("hana");
        AppUser member = user("minsu");
        FamilyCategory first = category(departing);
        FamilyCategory second = category(departing);
        addMember(first, member);
        addMember(second, member);
        AppUser outsider = user("admin");
        assertThatThrownBy(() -> service.transferForWithdrawal(departing.getId(),
                Map.of(first.getId(), member.getId(), second.getId(), outsider.getId())))
                .isInstanceOf(BadRequestException.class);
        assertThat(first.getOwner().getId()).isEqualTo(departing.getId());
        assertThat(second.getOwner().getId()).isEqualTo(departing.getId());
    }

    @Test
    void noActiveMemberBlocksTransferEvenIfTheirPhotoStillExists() {
        AppUser departing = user("hana");
        AppUser inactive = user("minsu");
        FamilyCategory category = category(departing);
        addMember(category, inactive);
        photo(category, inactive);
        inactive.setActive(false);
        users.saveAndFlush(inactive);
        var choices = service.requiredTransfers(departing.getId());
        assertThat(choices).hasSize(1);
        assertThat(choices.get(0).eligibleMembers()).isEmpty();
        assertThatThrownBy(() -> service.transferForWithdrawal(departing.getId(), Map.of(category.getId(), inactive.getId())))
                .isInstanceOf(BadRequestException.class);
        assertThat(category.getOwner().getId()).isEqualTo(departing.getId());
    }

    @Test
    void albumCreatedInAnotherUsersCategoryTransfersToThatCategoryOwner() {
        AppUser departing = user("hana");
        AppUser owner = user("minsu");
        FamilyCategory category = category(owner);
        FamilyAlbum album = album(category, departing);
        assertThat(service.requiredTransfers(departing.getId())).isEmpty();
        service.transferForWithdrawal(departing.getId(), Map.of());
        assertThat(album.getOwner().getId()).isEqualTo(owner.getId());
    }

    private AppUser user(String loginId) { return users.findByLoginId(loginId).orElseThrow(); }
    private FamilyCategory category(AppUser owner) {
        FamilyCategory value = new FamilyCategory();
        value.setOwner(owner);
        value.setName("가족 앨범");
        return categories.saveAndFlush(value);
    }
    private void addMember(FamilyCategory category, AppUser user) {
        FamilyCategoryMember value = new FamilyCategoryMember();
        value.setCategory(category);
        value.setUser(user);
        members.saveAndFlush(value);
    }
    private FamilyAlbum album(FamilyCategory category, AppUser owner) {
        FamilyAlbum value = new FamilyAlbum();
        value.setCategory(category);
        value.setOwner(owner);
        value.setTitle("추억");
        return albums.saveAndFlush(value);
    }
    private FamilyMediaAsset photo(FamilyCategory category, AppUser owner) {
        FamilyMediaAsset value = new FamilyMediaAsset();
        value.setCategory(category);
        value.setOwner(owner);
        value.setOriginalFileName("photo.jpg");
        value.setStoredFileName("photo.jpg");
        value.setStoragePath("other-user/photo.jpg");
        value.setContentType("image/jpeg");
        value.setFileSize(1L);
        return media.saveAndFlush(value);
    }
    private void addItem(FamilyAlbum album, FamilyMediaAsset photo) {
        FamilyAlbumItem item = new FamilyAlbumItem();
        item.setAlbum(album); item.setMedia(photo);
        items.saveAndFlush(item);
    }
}
