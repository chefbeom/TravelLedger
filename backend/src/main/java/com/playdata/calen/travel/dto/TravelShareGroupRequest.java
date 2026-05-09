package com.playdata.calen.travel.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record TravelShareGroupRequest(
        @NotBlank String name,
        List<String> recipientLoginIds
) {
}
