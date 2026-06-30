package com.playdata.calen.account.dto;

public record AdminDataStorageControlUpdateRequest(
        Long minioStorageCapacityBytes
) {
}