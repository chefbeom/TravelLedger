package com.playdata.calen.travel.adapter.out.persistence;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.TravelRecordStore;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.model.TravelRecordData.*;
import com.playdata.calen.travel.adapter.out.storage.TravelAttachmentCleanup;
import com.playdata.calen.travel.repository.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaTravelRecordAdapter implements TravelRecordStore {
    private final TravelPlanRepository plans;
    private final TravelExpenseRecordRepository records;
    private final TravelMediaAssetRepository media;
    private final TravelAttachmentCleanup cleanup;

    @Override public Plan ownedPlan(Long owner, Long id) {
        return plan(plans.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("Travel plan not found.")));
    }
    @Override public Optional<Access> ownedRecord(Long owner, Long id) {
        return records.findByIdAndPlanOwnerId(id, owner).map(r -> new Access(r.getId(), plan(r.getPlan()), r.getRecordType()));
    }
    @Override public Long create(Long planId, Value value) {
        var record = new TravelExpenseRecord();
        record.setPlan(plans.getReferenceById(planId));
        apply(record, value);
        return records.save(record).getId();
    }
    @Override public void update(Long id, Value value) { apply(records.getReferenceById(id), value); }
    @Override public void removeMedia(Long owner, Long id) {
        cleanup.removeMediaFilesAndLinks(media.findAllByRecordIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(id, owner));
        media.deleteAllByRecordId(id);
    }
    @Override public void delete(Long id) { records.delete(records.getReferenceById(id)); }

    private Plan plan(TravelPlan plan) { return new Plan(plan.getId(), plan.getName(), plan.getColorHex()); }
    private void apply(TravelExpenseRecord record, Value value) {
        record.setRecordType(value.type());
        record.setExpenseDate(value.date());
        record.setExpenseTime(value.time());
        record.setCategory(value.category());
        record.setTitle(value.title());
        record.setAmount(value.amount());
        record.setCurrencyCode(value.currency());
        record.setExchangeRateToKrw(value.rate());
        record.setAmountKrw(value.amountKrw());
        record.setCountry(value.country());
        record.setRegion(value.region());
        record.setPlaceName(value.placeName());
        record.setLatitude(value.latitude());
        record.setLongitude(value.longitude());
        record.setSharedWithCommunity(value.shared());
        record.setMemo(value.memo());
    }
}
