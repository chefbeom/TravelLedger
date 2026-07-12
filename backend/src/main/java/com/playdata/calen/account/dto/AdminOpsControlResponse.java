package com.playdata.calen.account.dto;

public record AdminOpsControlResponse(
        AdminAiControlResponse ai,
        AdminAiServerStatusResponse aiServer,
        AdminAiServerStatusResponse imageAiServer,
        AdminDataServerStatusResponse dataServer,
        String persistenceMessage
) {
}