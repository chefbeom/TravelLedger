package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AccountEmailVerificationRepository;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.EmailVerificationTokenRepository;
import com.playdata.calen.account.security.AccountRememberMeTokens;
import com.playdata.calen.account.withdrawal.PendingWithdrawalGuard;
import com.playdata.calen.account.withdrawal.WithdrawalChallengeRevoker;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveAdminConfigRepository;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveShareRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.access.AccessDeniedException;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.schema.legacy-updaters.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class DriveAdminServiceQueryCountTest {
    @Autowired private EntityManager entityManager;
    @Autowired private AppUserRepository users;
    @Autowired private DriveItemRepository items;
    @Autowired private DriveShareRepository shares;
    @Autowired private DriveAdminConfigRepository configs;

    @Test
    void storageAnalyticsQueryCountDoesNotGrowWithUserCountAndKeepsTotals() {
        DriveStorageService storage = mock(DriveStorageService.class);
        when(storage.objectExists(anyString())).thenReturn(true);
        DriveAdminService service = new DriveAdminService(users, items, shares, configs, storage,
                mock(PendingWithdrawalGuard.class), mock(WithdrawalChallengeRevoker.class),
                mock(EmailVerificationTokenRepository.class), mock(AccountEmailVerificationRepository.class),
                mock(AccountRememberMeTokens.class), entityManager);

        service.getStorageAnalytics(true); // creates the admin config row once so counts compare like for like
        List<AppUser> owners = new ArrayList<>();
        for (int i = 0; i < 3; i++) owners.add(createUserWithFiles("small-" + i, i == 0));
        long smallCount = measureStatements(service);

        for (int i = 0; i < 7; i++) owners.add(createUserWithFiles("large-" + i, false));
        long largeCount = measureStatements(service);

        assertThat(largeCount).isEqualTo(smallCount);

        DriveDtos.StorageAnalyticsResponse response = service.getStorageAnalytics(true);
        assertThat(response.users()).hasSize(10);
        DriveDtos.AdminUserResponse withTrash = response.users().stream()
                .filter(user -> user.id().equals(owners.get(0).getId())).findFirst().orElseThrow();
        assertThat(withTrash.fileCount()).isEqualTo(2L);
        assertThat(withTrash.trashedCount()).isEqualTo(1L);
        assertThat(withTrash.usedBytes()).isEqualTo(300L);
        DriveDtos.AdminUserResponse plain = response.users().stream()
                .filter(user -> user.id().equals(owners.get(1).getId())).findFirst().orElseThrow();
        assertThat(plain.fileCount()).isEqualTo(2L);
        assertThat(plain.trashedCount()).isZero();
        assertThat(plain.usedBytes()).isEqualTo(300L);
        assertThat(response.providerUsedBytes()).isEqualTo(3000L);
    }

    @Test
    void storageAnalyticsRequiresAdmin() {
        DriveAdminService service = new DriveAdminService(users, items, shares, configs, mock(DriveStorageService.class),
                mock(PendingWithdrawalGuard.class), mock(WithdrawalChallengeRevoker.class),
                mock(EmailVerificationTokenRepository.class), mock(AccountEmailVerificationRepository.class),
                mock(AccountRememberMeTokens.class), entityManager);
        assertThatThrownBy(() -> service.getStorageAnalytics(false)).isInstanceOf(AccessDeniedException.class);
    }

    private long measureStatements(DriveAdminService service) {
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        service.getStorageAnalytics(true);
        return statistics.getPrepareStatementCount();
    }

    private AppUser createUserWithFiles(String login, boolean withTrashedFile) {
        AppUser user = new AppUser();
        user.setLoginId(login);
        user.setDisplayName(login);
        user.setPasswordHash("test-only-hash");
        entityManager.persist(user);
        entityManager.persist(file(user, login + "-a", 100, false));
        entityManager.persist(file(user, login + "-b", 200, withTrashedFile));
        return user;
    }

    private DriveItem file(AppUser owner, String name, long size, boolean trashed) {
        DriveItem item = new DriveItem();
        item.setOwner(owner);
        item.setOriginalName(name);
        item.setStoredName(name);
        item.setStoragePath("test-only/" + name);
        item.setItemType(DriveItemType.FILE);
        item.setFileSize(size);
        item.setTrashed(trashed);
        return item;
    }
}
