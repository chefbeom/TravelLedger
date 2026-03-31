package com.playdata.calen.account.dto;

import java.util.List;

public record AdminDataStatSectionResponse(
        String key,
        String title,
        List<AdminDataStatItemResponse> items
) {
}
