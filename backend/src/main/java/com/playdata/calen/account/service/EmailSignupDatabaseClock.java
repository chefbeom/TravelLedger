package com.playdata.calen.account.service;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A shared clock keeps newly issued signup deadlines consistent across application nodes. */
@Service
@RequiredArgsConstructor
public class EmailSignupDatabaseClock {
    private final JdbcTemplate jdbc;

    public LocalDateTime now() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp",
                (row, index) -> row.getObject(1, LocalDateTime.class));
        if (now == null) throw new ServiceUnavailableException("가입 인증 저장소의 시간을 확인할 수 없습니다.");
        return now;
    }
}
