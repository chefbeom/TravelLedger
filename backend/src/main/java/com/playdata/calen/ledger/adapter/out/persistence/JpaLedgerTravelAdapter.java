package com.playdata.calen.ledger.adapter.out.persistence;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.ledger.application.port.out.LedgerTravelStore;
import com.playdata.calen.ledger.domain.*;
import com.playdata.calen.ledger.domain.model.LedgerTravelData.*;
import com.playdata.calen.ledger.repository.*;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.domain.policy.TravelPlanPolicy;
import com.playdata.calen.travel.repository.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaLedgerTravelAdapter implements LedgerTravelStore {
    private final AppUserRepository users;
    private final TravelPlanRepository plans;
    private final TravelExpenseRecordRepository records;
    private final LedgerEntryRepository entries;
    private final CategoryGroupRepository groups;
    private final CategoryDetailRepository details;
    private final PaymentMethodRepository payments;
    @Override public Optional<Travel> ownedTravel(Long owner, Long id) {
        return records.findByIdAndPlanOwnerId(id, owner).map(r -> new Travel(r.getId(), plan(r.getPlan()), r.getRecordType() == null ? null : r.getRecordType().name(),
                r.getExpenseDate(), r.getExpenseTime(), r.getCategory(), r.getTitle(), r.getAmount(), r.getCurrencyCode(), r.getExchangeRateToKrw(), r.getAmountKrw(),
                r.getCountry(), r.getRegion(), r.getPlaceName(), r.getMemo()));
    }
    @Override public Optional<Plan> ownedPlan(Long owner, Long id) { return plans.findByIdAndOwnerId(id, owner).map(this::plan); }
    @Override public Optional<Entry> ownedEntry(Long owner, Long id) {
        return entries.findByIdAndOwnerIdAndDeletedAtIsNull(id, owner).map(e -> new Entry(e.getId(), e.getOwner().getId(), e.getEntryType(), e.getEntryDate(), e.getEntryTime(), e.getTitle(),
                e.getMemo(), e.getAmount(), e.getForeignCurrencyCode(), e.getForeignAmount(), e.getExchangeRateToKrw(),
                e.getCategoryGroup() == null ? null : new Named(e.getCategoryGroup().getId(), e.getCategoryGroup().getName()),
                e.getCategoryDetail() == null ? null : new Named(e.getCategoryDetail().getId(), e.getCategoryDetail().getName()),
                e.getPaymentMethod() == null ? null : new Ref(e.getPaymentMethod().getId()), e.getTravelPlanId(), e.getTravelRecordId()));
    }
    @Override public Optional<LinkedTravel> linkedTravel(Long owner, Long id) {
        return records.findByIdAndPlanOwnerIdAndRecordType(id, owner, TravelRecordType.LEDGER).map(r -> new LinkedTravel(r.getId(), r.getPlan().getId(), r.getSharedWithCommunity()));
    }
    @Override public Optional<Long> linkedLedger(Long owner, Long record) {
        return entries.findByOwnerIdAndTravelRecordIdAndDeletedAtIsNull(owner, record).map(LedgerEntry::getId);
    }
    @Override public Long group(Long owner, String name) {
        var existing = groups.findFirstByOwnerIdAndEntryTypeAndNameIgnoreCaseOrderByIdAsc(owner, EntryType.EXPENSE, name);
        if (existing.isPresent()) { var group = existing.get(); if (!group.isActive()) group.setActive(true); return group.getId(); }
        var group = new CategoryGroup(); group.setOwner(users.getReferenceById(owner)); group.setName(name); group.setEntryType(EntryType.EXPENSE);
        group.setDisplayOrder(Integer.MAX_VALUE - 20); group.setActive(true); return groups.save(group).getId();
    }
    @Override public Long detail(Long group, String lookupName, String createName) {
        var existing = details.findFirstByGroupIdAndNameIgnoreCaseOrderByIdAsc(group, lookupName);
        if (existing.isPresent()) { var detail = existing.get(); if (!detail.isActive()) detail.setActive(true); return detail.getId(); }
        var detail = new CategoryDetail(); detail.setGroup(groups.getReferenceById(group)); detail.setName(createName);
        detail.setDisplayOrder(Integer.MAX_VALUE - 20); detail.setActive(true); return details.save(detail).getId();
    }
    @Override public Long payment(Long owner, String name) {
        var existing = payments.findFirstByOwnerIdAndNameIgnoreCaseOrderByIdAsc(owner, name);
        if (existing.isPresent()) { var payment = existing.get(); if (!payment.isActive()) payment.setActive(true); return payment.getId(); }
        var payment = new PaymentMethod(); payment.setOwner(users.getReferenceById(owner)); payment.setName(name); payment.setKind(PaymentMethodKind.OTHER);
        payment.setDisplayOrder(Integer.MAX_VALUE - 20); payment.setActive(true); return payments.save(payment).getId();
    }
    @Override public Long saveTravel(Long plan, Long id, TravelValue value) {
        var record = id == null ? new TravelExpenseRecord() : records.getReferenceById(id);
        record.setPlan(plans.getReferenceById(plan)); record.setRecordType(TravelRecordType.LEDGER);
        record.setExpenseDate(value.date()); record.setExpenseTime(value.time()); record.setCategory(value.category()); record.setTitle(value.title());
        record.setAmount(value.amount()); record.setCurrencyCode(value.currency()); record.setExchangeRateToKrw(value.rate()); record.setAmountKrw(value.amountKrw());
        record.setSharedWithCommunity(value.shared()); record.setMemo(value.memo());
        return records.save(record).getId();
    }
    @Override public TravelResult travelResult(Long id) {
        var r = records.getReferenceById(id); var p = r.getPlan(); var color = TravelPlanPolicy.color(p.getColorHex());
        return new TravelResult(r.getId(), p.getId(), p.getName(), color, r.getRecordType() == null ? null : r.getRecordType().name(), r.getExpenseDate(), r.getExpenseTime(),
                r.getCategory(), r.getTitle(), r.getAmount(), r.getCurrencyCode(), r.getExchangeRateToKrw(), r.getAmountKrw(), r.getCountry(), r.getRegion(), r.getPlaceName(),
                r.getLatitude(), r.getLongitude(), r.getMemo());
    }
    private Plan plan(TravelPlan p) { return new Plan(p.getId(), p.getOwner().getId(), p.getName(), p.getStartDate(), p.getEndDate()); }
}
