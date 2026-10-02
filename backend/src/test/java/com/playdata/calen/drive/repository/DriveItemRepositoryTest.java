package com.playdata.calen.drive.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.schema.legacy-updaters.enabled=false"
})
class DriveItemRepositoryTest {
    @Autowired private TestEntityManager entityManager;
    @Autowired private DriveItemRepository repository;

    @Test
    void aggregateAndLimitedQueriesKeepOwnerTrashAndFolderSemantics() {
        AppUser owner = owner("owner");
        AppUser other = owner("other");
        DriveItem folder = item(owner, "folder", DriveItemType.FOLDER, 0, false, false);
        DriveItem activeFile = item(owner, "active", DriveItemType.FILE, 100, false, true);
        activeFile.setParent(folder);
        entityManager.persistAndFlush(activeFile);
        DriveItem trashFile = item(owner, "trash", DriveItemType.FILE, 50, true, false);
        item(other, "foreign", DriveItemType.FILE, 999, false, true);
        entityManager.clear();

        DriveItemRepository.HomeAggregate aggregate = repository.aggregateHome(owner.getId());
        assertThat(aggregate.getItemCount()).isEqualTo(3L);
        assertThat(aggregate.getFileCount()).isEqualTo(2L);
        assertThat(aggregate.getFolderCount()).isEqualTo(1L);
        assertThat(aggregate.getSharedCount()).isEqualTo(1L);
        assertThat(aggregate.getTrashCount()).isEqualTo(1L);
        assertThat(aggregate.getUsedBytes()).isEqualTo(150L);
        assertThat(repository.findAllByOwner_IdOrderByLastModifiedAtDescIdDesc(owner.getId(), PageRequest.of(0, 2)))
                .hasSize(2).extracting(DriveItem::getId).contains(trashFile.getId());
        assertThat(repository.findAllByOwner_IdAndItemTypeAndTrashedFalseOrderByLastModifiedAtDescIdDesc(
                owner.getId(), DriveItemType.FILE, PageRequest.of(0, 30)))
                .extracting(DriveItem::getId).containsExactly(activeFile.getId());
        assertThat(repository.findAllByOwner_IdAndParent_Id(owner.getId(), folder.getId()))
                .extracting(DriveItem::getId).containsExactly(activeFile.getId());
        assertThat(repository.findAllByOwner_IdAndParent_Id(other.getId(), folder.getId())).isEmpty();
    }

    @Test
    void emptyOwnerAggregateHasZeroItems() {
        AppUser owner = owner("empty");
        assertThat(repository.aggregateHome(owner.getId()).getItemCount()).isZero();
        assertThat(repository.findAllByOwner_IdOrderByLastModifiedAtDescIdDesc(owner.getId(), PageRequest.of(0, 8))).isEmpty();
    }

    private AppUser owner(String login) {
        AppUser user = new AppUser();
        user.setLoginId(login);
        user.setDisplayName(login);
        user.setPasswordHash("test-only-hash");
        return entityManager.persistAndFlush(user);
    }

    private DriveItem item(AppUser owner, String name, DriveItemType type, long size, boolean trash, boolean shared) {
        DriveItem item = new DriveItem();
        item.setOwner(owner);
        item.setOriginalName(name);
        item.setStoredName(name);
        item.setItemType(type);
        item.setFileSize(size);
        item.setTrashed(trash);
        item.setSharedFile(shared);
        return entityManager.persistAndFlush(item);
    }
}
