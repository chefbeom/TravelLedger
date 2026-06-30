package com.playdata.calen.account.dto;

import java.util.List;

public record AdminAiServerStatusResponse(
        boolean reachable,
        String provider,
        String baseUrl,
        String modelsPath,
        long latencyMillis,
        List<String> models,
        String message
) {
}