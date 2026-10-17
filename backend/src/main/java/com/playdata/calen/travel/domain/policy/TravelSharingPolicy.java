package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.domain.TravelPlanStatus;

public final class TravelSharingPolicy {
    private TravelSharingPolicy() {}

    public static void requireCompleted(TravelPlanStatus status) {
        if (TravelPlanPolicy.status(status) != TravelPlanStatus.COMPLETED) {
            throw new BadRequestException("완성된 여행만 다른 사용자에게 공유할 수 있습니다.");
        }
    }

    public static String recipientLogin(String raw) {
        String login = trimToNull(raw);
        if (login == null) {
            throw new BadRequestException("공유할 사용자 아이디를 입력해주세요.");
        }
        return login;
    }

    public static String groupName(String raw) {
        String name = trimToNull(raw);
        if (name == null) {
            throw new BadRequestException("공유 그룹 이름을 입력해주세요.");
        }
        return name;
    }

    public static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }
}
