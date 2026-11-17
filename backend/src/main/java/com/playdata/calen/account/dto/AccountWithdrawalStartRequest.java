package com.playdata.calen.account.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Ownership choices are revalidated inside the withdrawal transaction. */
public record AccountWithdrawalStartRequest(
        @NotBlank @Pattern(regexp = "[0-9]{8}") String secondaryPin,
        @NotNull @Size(max = 1000) Map<@NotNull @Positive Long, @NotNull @Positive Long> familyOwners,
        @NotNull @Size(max = 1000) Map<@NotNull @Positive Long, @NotNull @Positive Long> groupOwners,
        @NotNull @Size(max = 1000) Map<@NotNull @Positive Long, @NotNull @Positive Long> travelPlanOwners,
        @AssertTrue boolean acknowledged
) {}
