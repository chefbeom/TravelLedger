package com.playdata.calen.travel.domain.model;

import com.playdata.calen.travel.domain.TravelPlanStatus;
import java.time.LocalDateTime;
import java.util.List;

public final class TravelSharingData {
    private TravelSharingData() {}

    public record Recipient(Long id, String loginId, String displayName) {}
    public record Plan(Long id, TravelPlanStatus status) {}
    public record Share(Long id, Long planId, String planName, String recipientLoginId,
            String recipientDisplayName, LocalDateTime sharedAt) {}
    public record Group(Long id, String name, List<Recipient> recipients, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record PublicShare(Long planId, boolean publicShared, LocalDateTime publicSharedAt) {}
}
