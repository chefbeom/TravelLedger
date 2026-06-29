package com.playdata.calen.account.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

public record DataPortabilityExportRequest(
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate from,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate to
) {
}