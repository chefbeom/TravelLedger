package com.playdata.calen.travel.dto;

public record TravelShareRecipientResponse(
        Long userId,
        String loginId,
        String displayName
) {
}
