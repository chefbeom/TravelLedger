package com.playdata.calen.travel.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.service.GeoBounds;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.schema.legacy-updaters.enabled=false"})
class TravelNearestMemoryRepositoryTest {
    @Autowired private TestEntityManager entityManager;
    @Autowired private TravelExpenseRecordRepository repository;
    @Autowired private TravelMediaAssetRepository mediaRepository;

    @Test
    void clusterPhotoPagesApplyFocusAndRepresentativeOrderAndOwnerScopeInSql() {
        TravelPlan ownerPlan = plan("photos");
        var first = photo(ownerPlan, 100L, ownerPlan.getOwner().getId());
        var representative = photo(ownerPlan, 100L, ownerPlan.getOwner().getId());
        var newest = photo(ownerPlan, 100L, ownerPlan.getOwner().getId());
        photo(plan("foreign-photos"), 100L, ownerPlan.getOwner().getId());
        photo(ownerPlan, 101L, ownerPlan.getOwner().getId());
        entityManager.clear();

        var page = mediaRepository.findClusterPhotoPage(ownerPlan.getOwner().getId(), 100L,
                first.getId(), representative.getId(), PageRequest.of(0, 2));

        assertThat(page.getContent()).extracting(com.playdata.calen.travel.domain.TravelMediaAsset::getId)
                .containsExactly(first.getId(), representative.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
        assertThat(mediaRepository.findClusterPhotoPage(ownerPlan.getOwner().getId(), 100L,
                first.getId(), representative.getId(), PageRequest.of(1, 2)).getContent())
                .extracting(com.playdata.calen.travel.domain.TravelMediaAsset::getId).containsExactly(newest.getId());
    }

    @Test
    void nearestCandidatesCrossDateLineAndRemainBoundedAndOwnerScoped() {
        TravelPlan plan = plan("owner");
        TravelExpenseRecord origin = memory(plan, 0, 179.9999);
        TravelExpenseRecord close = memory(plan, 0, -179.9999);
        memory(plan, 0, 179.9995);
        memory(plan, 0, 179.99);
        memory(plan("other"), 0, 179.99991);
        GeoBounds bounds = GeoBounds.around(0, 179.9999, 100);
        assertThat(bounds.crossesDateLine()).isTrue();
        entityManager.clear();

        assertThat(repository.findNearestMemoryCandidates(plan.getOwner().getId(), TravelRecordType.MEMORY,
                origin.getId(), 0, 179.9999, bounds.minLatitude(), bounds.maxLatitude(),
                bounds.minLongitude(), bounds.maxLongitude(), bounds.allLongitudes(), bounds.crossesDateLine(),
                PageRequest.of(0, 1))).extracting(TravelExpenseRecord::getId).containsExactly(close.getId());
    }

    @Test
    void radiusTouchingPoleCoversAllLongitudes() {
        GeoBounds bounds = GeoBounds.around(89.99999, 90, 5);
        assertThat(bounds.allLongitudes()).isTrue();
        assertThat(bounds.maxLatitude()).isEqualTo(90);
        assertThat(GeoBounds.around(0, 0, Math.PI * 6_371_000d).minLatitude()).isEqualTo(-90);
    }

    private TravelPlan plan(String name) {
        AppUser owner = new AppUser();
        owner.setLoginId(name); owner.setDisplayName(name); owner.setPasswordHash("test-only-hash");
        entityManager.persist(owner);
        TravelPlan plan = new TravelPlan();
        plan.setOwner(owner); plan.setName(name); plan.setStartDate(LocalDate.of(2026, 1, 1)); plan.setEndDate(plan.getStartDate());
        return entityManager.persistAndFlush(plan);
    }

    private com.playdata.calen.travel.domain.TravelMediaAsset photo(TravelPlan plan, Long clusterId, Long memberOwnerId) {
        var photo = new com.playdata.calen.travel.domain.TravelMediaAsset();
        photo.setPlan(plan); photo.setRecord(memory(plan, 0, 0)); photo.setUploadedBy(plan.getOwner());
        photo.setOriginalFileName("photo.jpg"); photo.setStoredFileName(java.util.UUID.randomUUID() + ".jpg");
        photo.setStoragePath("test-only"); photo.setContentType("image/jpeg"); photo.setFileSize(1L);
        entityManager.persistAndFlush(photo);
        var member = new com.playdata.calen.travel.domain.TravelPhotoClusterMember();
        member.setOwnerId(memberOwnerId); member.setClusterId(clusterId); member.setMediaId(photo.getId()); member.setSortOrder(0);
        entityManager.persistAndFlush(member);
        return photo;
    }

    private TravelExpenseRecord memory(TravelPlan plan, double latitude, double longitude) {
        TravelExpenseRecord record = new TravelExpenseRecord();
        record.setPlan(plan); record.setRecordType(TravelRecordType.MEMORY);
        record.setExpenseDate(plan.getStartDate()); record.setCategory("spot"); record.setTitle("memory");
        record.setAmount(BigDecimal.ZERO); record.setLatitude(BigDecimal.valueOf(latitude));
        record.setLongitude(BigDecimal.valueOf(longitude));
        return entityManager.persistAndFlush(record);
    }
}
