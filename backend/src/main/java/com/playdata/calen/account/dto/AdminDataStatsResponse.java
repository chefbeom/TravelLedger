package com.playdata.calen.account.dto;

import java.util.List;

public record AdminDataStatsResponse(
        List<AdminDataStatSectionResponse> sections
) {
}
