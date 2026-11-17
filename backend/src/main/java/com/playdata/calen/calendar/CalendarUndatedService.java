package com.playdata.calen.calendar;

import com.playdata.calen.recordmap.domain.RecordPlace;
import com.playdata.calen.travel.domain.TravelPlan;
import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Undated material is explicitly listed, never assigned a created/uploaded date. */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class CalendarUndatedService {
    private final EntityManager entities;
    private static final int PAGE_SIZE = 30;
    public record Item(Long id, String title, String type) { }
    public record Page(List<Item> items, int page, int size, long total) { }
    public record Result(Page places, Page trips) { }

    public Result list(Long owner, int placePage, int tripPage) {
        int p = Math.max(0, Math.min(placePage, 100000)), t = Math.max(0, Math.min(tripPage, 100000));
        String placeCondition = """
                from RecordPlace p where p.ownerId = :owner and
                  (exists (select v.id from PlaceVisit v where v.ownerId = :owner and v.place.id = p.id and v.visitDate is null)
                   or (not exists (select r.id from TravelExpenseRecord r where r.id = p.sourceTravelRecordId and r.plan.owner.id = :owner)
                       and not exists (select v.id from PlaceVisit v where v.ownerId = :owner and v.place.id = p.id)))
                """;
        List<RecordPlace> places = entities.createQuery("select p " + placeCondition + " order by p.id", RecordPlace.class)
                .setParameter("owner", owner).setFirstResult(p * PAGE_SIZE).setMaxResults(PAGE_SIZE).getResultList();
        long placeCount = entities.createQuery("select count(p.id) " + placeCondition, Long.class).setParameter("owner", owner).getSingleResult();
        String tripCondition = "from TravelPlan p where p.owner.id = :owner and (p.startDate is null or p.endDate is null)";
        List<TravelPlan> trips = entities.createQuery("select p " + tripCondition + " order by p.id", TravelPlan.class)
                .setParameter("owner", owner).setFirstResult(t * PAGE_SIZE).setMaxResults(PAGE_SIZE).getResultList();
        long tripCount = entities.createQuery("select count(p.id) " + tripCondition, Long.class).setParameter("owner", owner).getSingleResult();
        return new Result(new Page(places.stream().map(place -> new Item(place.getId(), place.getName(), "PLACE")).toList(), p, PAGE_SIZE, placeCount),
                new Page(trips.stream().map(trip -> new Item(trip.getId(), trip.getName(), "TRIP")).toList(), t, PAGE_SIZE, tripCount));
    }
}
