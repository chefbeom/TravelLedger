package com.playdata.calen.account.dto;

import java.util.List;
import java.util.Map;

public record AdminAiRoutingUpdateRequest(
        List<AdminAiServerCandidateRequest> candidates,
        Map<String, String> featureConnections
) {
}