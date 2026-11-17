package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.withdrawal.AccountWithdrawalStartService;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.dto.RecordShareDtos;
import com.playdata.calen.sharing.service.RecordSharingService;
import com.playdata.calen.travel.application.port.in.ManageOwnedTravelMediaUseCase;
import com.playdata.calen.travel.application.port.in.ManageTravelSharingUseCase;
import com.playdata.calen.travel.application.port.in.ReadTravelExhibitsUseCase;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.account-withdrawal.internal-workflow-enabled=true",
        "app.security.withdrawal-task-seal-key=test-withdrawal-secret-at-least-32-characters",
        "spring.datasource.url=jdbc:h2:mem:travel-files-${random.uuid};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "minio.endpoint=", "minio.access-key=", "minio.secret-key="
})
class TravelPlanTransferFileIntegrationTest {
    @TempDir static Path temporaryStorage;
    @DynamicPropertySource static void temporaryPaths(DynamicPropertyRegistry properties) {
        properties.add("app.travel.media-storage-path", () -> temporaryStorage.toString());
    }

    @Autowired AppUserService users;
    @Autowired TravelPlanRepository plans;
    @Autowired TravelExpenseRecordRepository records;
    @Autowired TravelMediaAssetRepository media;
    @Autowired TravelMediaStorageService storage;
    @Autowired TravelDriveLinkService drive;
    @Autowired ManageTravelSharingUseCase sharing;
    @Autowired ManageOwnedTravelMediaUseCase ownedMedia;
    @Autowired ReadTravelExhibitsUseCase exhibits;
    @Autowired RecordSharingService recordSharing;
    @Autowired AccountWithdrawalStartService withdrawal;
    @Autowired DataSource dataSource;

    @BeforeEach void initializeIsolatedOutbox() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261006_032__ledger_embedding_sync_jobs.sql"))
                .execute(dataSource);
    }

    @Test void transferredPhotoAndThumbnailRemainReadableByNewOwnerAndOtherRecipient() throws Exception {
        AppUser owner = user(), successor = user(), recipient = user();
        TravelPlan plan = new TravelPlan(); plan.setOwner(owner); plan.setName("공유 여행");
        plan.setStartDate(LocalDate.now()); plan.setEndDate(LocalDate.now()); plan.setStatus(TravelPlanStatus.COMPLETED);
        plans.saveAndFlush(plan);
        TravelExpenseRecord record = new TravelExpenseRecord(); record.setPlan(plan); record.setRecordType(TravelRecordType.MEMORY);
        record.setExpenseDate(LocalDate.now()); record.setCategory("장소"); record.setTitle("사진 기록"); record.setAmount(BigDecimal.ZERO);
        records.saveAndFlush(record);
        byte[] bytes = image();
        var stored = storage.store(owner.getId(), plan.getId(), record.getId(),
                new MockMultipartFile("file", "photo.png", "image/png", bytes));
        assertThat(storage.loadPreparedThumbnail(stored.storagePath(), stored.contentType(), 320)).isNotNull();
        TravelMediaAsset photo = new TravelMediaAsset(); photo.setPlan(plan); photo.setRecord(record); photo.setUploadedBy(owner);
        photo.setOriginalFileName(stored.originalFileName()); photo.setStoredFileName(stored.storedFileName());
        photo.setStoragePath(stored.storagePath()); photo.setContentType(stored.contentType()); photo.setFileSize(stored.fileSize());
        media.saveAndFlush(photo);
        drive.linkMediaAsset(photo);
        sharing.shareCompletedPlan(owner.getId(), plan.getId(), successor.getLoginId());
        long recipientShare = sharing.shareCompletedPlan(owner.getId(), plan.getId(), recipient.getLoginId()).id();
        var group = sharing.saveGroup(owner.getId(), "공유 여행 그룹", List.of(successor.getLoginId(), recipient.getLoginId()));
        long groupShare = recordSharing.create(owner.getId(), new RecordShareDtos.Create(group.id(),
                RecordShareKind.TRAVEL, plan.getId(), List.of(recipient.getId()))).get(0).id();
        recordSharing.acceptTravel(recipient.getId(), groupShare);

        withdrawal.start(owner.getId(), "12345678", Map.of(), Map.of(group.id(), successor.getId()),
                Map.of(plan.getId(), successor.getId()));

        var ownerDownload = ownedMedia.download(successor.getId(), photo.getId());
        var recipientDownload = exhibits.receivedMedia(recipient.getId(), recipientShare, photo.getId());
        var groupDownload = recordSharing.getTravelMedia(recipient.getId(), groupShare, photo.getId());
        assertThat(ownerDownload.storagePath()).isEqualTo(stored.storagePath());
        assertThat(recipientDownload.storagePath()).isEqualTo(stored.storagePath());
        assertThat(groupDownload.storagePath()).isEqualTo(stored.storagePath());
        try (var input = storage.loadAsResource(ownerDownload.storagePath()).getInputStream()) {
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
        try (var input = storage.loadAsResource(recipientDownload.storagePath()).getInputStream()) {
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
        try (var input = storage.loadAsResource(groupDownload.storagePath()).getInputStream()) {
            assertThat(input.readAllBytes()).isEqualTo(bytes);
        }
        assertThat(storage.loadPreparedThumbnail(recipientDownload.storagePath(), recipientDownload.contentType(), 320)).isNotNull();
    }

    private AppUser user() {
        return users.registerUser("travel-" + UUID.randomUUID().toString().substring(0, 12), "테스트 회원", "test-password", "12345678");
    }
    private byte[] image() throws Exception {
        BufferedImage image = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
    }
}
