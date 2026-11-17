package com.playdata.calen.calendar;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.exception.PreferenceConflictException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class CalendarEventService {
    private final CalendarEventRepository events;
    private final AppUserRepository users;

    public record Draft(String title, LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime,
                        boolean allDay, String memo, Long version) { }
    public record View(Long id, String title, LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime,
                       boolean allDay, String memo, Long version) { }

    public List<View> list(Long owner, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 400)
            throw new BadRequestException("조회 날짜 범위를 확인해 주세요.");
        return events.findOverlapping(owner, from, to).stream().map(CalendarEventService::view).toList();
    }

    @Transactional public View create(Long owner, Draft draft) {
        validate(draft);
        var user = users.findActiveForPreferenceUpdate(owner).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        var event = new CalendarEvent();
        event.setOwner(user);
        apply(event, draft);
        return view(events.saveAndFlush(event));
    }

    @Transactional public View update(Long owner, Long id, Draft draft) {
        validate(draft);
        users.findActiveForPreferenceUpdate(owner).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        var event = events.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("일정을 찾을 수 없습니다."));
        if (draft.version() == null || !draft.version().equals(event.getVersion())) throw new PreferenceConflictException();
        apply(event, draft);
        return view(events.saveAndFlush(event));
    }

    @Transactional public void delete(Long owner, Long id, Long version) {
        users.findActiveForPreferenceUpdate(owner).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        var event = events.findByIdAndOwnerId(id, owner).orElseThrow(() -> new NotFoundException("일정을 찾을 수 없습니다."));
        if (version == null || !version.equals(event.getVersion())) throw new PreferenceConflictException();
        events.delete(event);
    }

    private static void validate(Draft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank() || draft.title().strip().length() > 160
                || draft.startDate() == null || (draft.memo() != null && draft.memo().length() > 4000))
            throw new BadRequestException("일정 제목과 날짜를 확인해 주세요.");
        LocalDate end = draft.endDate() == null ? draft.startDate() : draft.endDate();
        if (end.isBefore(draft.startDate()) || (draft.allDay() && (draft.startTime() != null || draft.endTime() != null))
                || (end.equals(draft.startDate()) && draft.startTime() != null && draft.endTime() != null && draft.endTime().isBefore(draft.startTime())))
            throw new BadRequestException("일정의 시작·종료 날짜와 시간을 확인해 주세요.");
    }

    private static void apply(CalendarEvent event, Draft draft) {
        event.setTitle(draft.title().strip());
        event.setStartDate(draft.startDate());
        event.setEndDate(draft.endDate() == null ? draft.startDate() : draft.endDate());
        event.setStartTime(draft.startTime()); event.setEndTime(draft.endTime());
        event.setAllDay(draft.allDay()); event.setMemo(draft.memo());
    }

    private static View view(CalendarEvent event) {
        return new View(event.getId(), event.getTitle(), event.getStartDate(), event.getEndDate(), event.getStartTime(), event.getEndTime(), event.isAllDay(), event.getMemo(), event.getVersion());
    }
}
