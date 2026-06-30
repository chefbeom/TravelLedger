package com.playdata.calen.account.dto;

public record AdminOpsControlResponse(
        AdminAiControlResponse ai,
        AdminAiServerStatusResponse aiServer,
        AdminDataServerStatusResponse dataServer,
        String persistenceMessage
) {
}