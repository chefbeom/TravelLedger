package com.playdata.calen.calendar;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.EntryType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Date range summaries query the original owner-scoped rows, never copied data. */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class CalendarSummaryService {
    private final EntityManager entities;

    public record MoneyDay(LocalDate date, BigDecimal income, BigDecimal expense, long rowCount, long transactionCount) { }
    public record CategoryExpense(Long id, String name, BigDecimal amount) { }
    public record CountDay(LocalDate date, long count) { }
    public record RecordDay(LocalDate date, String type, long count, long importedCount) { }
    public record Period(Long id, String title, LocalDate startDate, LocalDate endDate, String status) { }
    public record EventSummary(Long id, String title, LocalDate startDate, LocalDate endDate) { }
    public record TravelSummary(List<Period> periods, List<CountDay> routes, List<RecordDay> records) { }

    private static void validate(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 62)
            throw new BadRequestException("달력 조회 기간을 확인해 주세요.");
    }

    public List<MoneyDay> ledger(Long owner, LocalDate from, LocalDate to, EntryType type, Long categoryId, Long paymentMethodId) {
        validate(from, to);
        var rows = entities.createQuery("""
                select e.entryDate,
                  coalesce(sum(case when e.financiallyExcluded = false and e.entryType = :income then e.amount else 0 end), 0),
                  coalesce(sum(case when e.financiallyExcluded = false and e.entryType = :expense then e.amount else 0 end), 0),
                  count(e),
                  count(distinct case when e.splitPaymentGroupId is not null and e.adjustmentType is null then e.splitPaymentGroupId else null end)
                    + sum(case when e.splitPaymentGroupId is null or e.adjustmentType is not null then 1 else 0 end)
                from LedgerEntry e where e.owner.id = :owner and e.deletedAt is null and e.entryDate between :from and :to
                  and (:type is null or e.entryType = :type)
                  and (:category is null or e.categoryGroup.id = :category)
                  and (:payment is null or e.paymentMethod.id = :payment)
                group by e.entryDate order by e.entryDate
                """, Object[].class)
                .setParameter("owner", owner).setParameter("from", from).setParameter("to", to)
                .setParameter("income", EntryType.INCOME).setParameter("expense", EntryType.EXPENSE)
                .setParameter("type", type).setParameter("category", categoryId).setParameter("payment", paymentMethodId).getResultList();
        return rows.stream().map(row -> new MoneyDay((LocalDate) row[0], (BigDecimal) row[1], (BigDecimal) row[2], ((Number) row[3]).longValue(), ((Number) row[4]).longValue())).toList();
    }

    public List<CountDay> places(Long owner, LocalDate from, LocalDate to, Long collectionId) {
        return places(owner, from, to, collectionId, null);
    }

    public List<CountDay> places(Long owner, LocalDate from, LocalDate to, Long collectionId, Long planId) {
        validate(from, to);
        var rows = planId == null ? entities.createQuery("""
                select v.visitDate, count(v.id) from PlaceVisit v
                where v.ownerId = :owner and v.place.ownerId = :owner and v.visitDate between :from and :to
                  and (:collection is null or exists (select c.id from RecordPlace p join p.collections c where p.id = v.place.id and c.ownerId = :owner and c.id = :collection))
                group by v.visitDate order by v.visitDate
                """, Object[].class).setParameter("owner", owner).setParameter("from", from).setParameter("to", to)
                .setParameter("collection", collectionId).getResultList() : List.<Object[]>of();
        Map<LocalDate, Long> counts = new TreeMap<>();
        for (Object[] row : rows) counts.merge((LocalDate) row[0], ((Number) row[1]).longValue(), Long::sum);
        var imported = entities.createQuery("""
                select r.expenseDate, count(p.id) from RecordPlace p, TravelExpenseRecord r
                where p.ownerId = :owner and r.plan.owner.id = :owner and p.sourceTravelRecordId = r.id and r.expenseDate between :from and :to and (:plan is null or r.plan.id = :plan)
                  and (:collection is null or exists (select c.id from RecordPlace linked join linked.collections c where linked.id = p.id and c.ownerId = :owner and c.id = :collection))
                group by r.expenseDate order by r.expenseDate
                """, Object[].class).setParameter("owner", owner).setParameter("from", from).setParameter("to", to).setParameter("collection", collectionId).setParameter("plan", planId).getResultList();
        for (Object[] row : imported) counts.merge((LocalDate) row[0], ((Number) row[1]).longValue(), Long::sum);
        return counts.entrySet().stream().map(item -> new CountDay(item.getKey(), item.getValue())).toList();
    }

    public List<CategoryExpense> expenseCategories(Long owner, LocalDate from, LocalDate to, EntryType type, Long categoryId, Long paymentMethodId) {
        validate(from, to);
        if (type == EntryType.INCOME) return List.of();
        return entities.createQuery("""
                select c.id, c.name, sum(e.amount) from LedgerEntry e join e.categoryGroup c
                where e.owner.id = :owner and e.deletedAt is null and e.financiallyExcluded = false and e.entryType = :expense
                  and e.entryDate between :from and :to and (:category is null or c.id = :category)
                  and (:payment is null or e.paymentMethod.id = :payment)
                group by c.id, c.name order by sum(e.amount) desc, c.id
                """, Object[].class).setParameter("owner", owner).setParameter("expense", EntryType.EXPENSE)
                .setParameter("from", from).setParameter("to", to).setParameter("category", categoryId).setParameter("payment", paymentMethodId)
                .getResultList().stream().map(row -> new CategoryExpense((Long) row[0], (String) row[1], (BigDecimal) row[2])).toList();
    }

    public TravelSummary travel(Long owner, LocalDate from, LocalDate to, Long planId) {
        return travel(owner, from, to, planId, null);
    }

    public TravelSummary travel(Long owner, LocalDate from, LocalDate to, Long planId, Long collectionId) {
        validate(from, to);
        var periods = entities.createQuery("""
                select p from TravelPlan p where p.owner.id = :owner and p.startDate is not null and p.endDate is not null
                  and p.startDate <= :to and p.endDate >= :from and (:plan is null or p.id = :plan)
                order by p.startDate, p.id
                """, com.playdata.calen.travel.domain.TravelPlan.class)
                .setParameter("owner", owner).setParameter("from", from).setParameter("to", to).setParameter("plan", planId).getResultList()
                .stream().map(p -> new Period(p.getId(), p.getName(), p.getStartDate(), p.getEndDate(), p.getStatus() == null ? null : p.getStatus().name())).toList();
        var routeRows = entities.createQuery("""
                select r.routeDate, count(r.id) from TravelRouteSegment r
                where r.plan.owner.id = :owner and r.routeDate between :from and :to and (:plan is null or r.plan.id = :plan)
                group by r.routeDate order by r.routeDate
                """, Object[].class).setParameter("owner", owner).setParameter("from", from).setParameter("to", to).setParameter("plan", planId).getResultList();
        var recordRows = entities.createQuery("""
                select r.expenseDate, r.recordType, count(r.id) from TravelExpenseRecord r
                where r.plan.owner.id = :owner and r.expenseDate between :from and :to and (:plan is null or r.plan.id = :plan)
                group by r.expenseDate, r.recordType order by r.expenseDate
                """, Object[].class).setParameter("owner", owner).setParameter("from", from).setParameter("to", to).setParameter("plan", planId).getResultList();
        var importedRows = entities.createQuery("""
                select r.expenseDate, r.recordType, count(r.id) from TravelExpenseRecord r
                where r.plan.owner.id = :owner and r.expenseDate between :from and :to and (:plan is null or r.plan.id = :plan)
                  and exists (select p.id from RecordPlace p where p.ownerId = :owner and p.sourceTravelRecordId = r.id
                    and (:collection is null or exists (select c.id from RecordPlace linked join linked.collections c where linked.id = p.id and c.ownerId = :owner and c.id = :collection)))
                group by r.expenseDate, r.recordType
                """, Object[].class).setParameter("owner", owner).setParameter("from", from).setParameter("to", to)
                .setParameter("plan", planId).setParameter("collection", collectionId).getResultList();
        Map<LocalDate, Map<String, long[]>> recordCounts = new TreeMap<>();
        for (Object[] row : recordRows) {
            String type = row[1] == null ? "LEDGER" : row[1].toString();
            recordCounts.computeIfAbsent((LocalDate) row[0], ignored -> new TreeMap<>()).computeIfAbsent(type, ignored -> new long[2])[0] += ((Number) row[2]).longValue();
        }
        for (Object[] row : importedRows) {
            String type = row[1] == null ? "LEDGER" : row[1].toString();
            recordCounts.computeIfAbsent((LocalDate) row[0], ignored -> new TreeMap<>()).computeIfAbsent(type, ignored -> new long[2])[1] += ((Number) row[2]).longValue();
        }
        return new TravelSummary(periods,
                routeRows.stream().map(row -> new CountDay((LocalDate) row[0], ((Number) row[1]).longValue())).toList(),
                recordCounts.entrySet().stream().flatMap(date -> date.getValue().entrySet().stream()
                        .map(item -> new RecordDay(date.getKey(), item.getKey(), item.getValue()[0], item.getValue()[1]))).toList());
    }

    public List<EventSummary> personalEvents(Long owner, LocalDate from, LocalDate to) {
        validate(from, to);
        return entities.createQuery("select e.id, e.title, e.startDate, e.endDate from CalendarEvent e where e.owner.id = :owner and e.startDate <= :to and e.endDate >= :from order by e.startDate, e.id", Object[].class)
                .setParameter("owner", owner).setParameter("from", from).setParameter("to", to).getResultList().stream()
                .map(row -> new EventSummary((Long) row[0], (String) row[1], (LocalDate) row[2], (LocalDate) row[3])).toList();
    }
}
