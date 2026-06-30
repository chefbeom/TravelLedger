package com.playdata.calen.account.dto;

public record AdminDataServerStatusResponse(
        boolean databaseReachable,
        String databaseProduct,
        String databaseHost,
        String databaseMessage,
        AdminMinioStorageSummaryResponse minioStorage
) {
}