package com.playdata.calen.account.dto;

public record AdminOpsControlResponse(
        AdminAiControlResponse ai,
        AdminAiServerStatusResponse aiServer,
        AdminAiServerStatusResponse imageAiServer,
        AdminAiServerStatusResponse excelAiServer,
        AdminDataServerStatusResponse dataServer,
        String persistenceMessage
) {
}