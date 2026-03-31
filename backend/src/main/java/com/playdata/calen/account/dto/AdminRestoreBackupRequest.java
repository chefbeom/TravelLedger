package com.playdata.calen.account.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminRestoreBackupRequest(
        @NotBlank(message = "복구할 백업 파일을 선택해 주세요.")
        String fileName
) {
}
