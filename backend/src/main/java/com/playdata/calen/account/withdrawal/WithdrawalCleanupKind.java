package com.playdata.calen.account.withdrawal;

public enum WithdrawalCleanupKind {
    KAKAO_UNLINK,
    FAMILY_MEDIA,
    TRAVEL_MEDIA,
    // Inventory only. This is deliberately not an authorization to delete an object.
    TRAVEL_FILE_INVENTORY,
    // Inventory only; this is not authority to delete a profile object.
    DRIVE_PROFILE_INVENTORY,
    DRIVE_FILE_INVENTORY,
    DRIVE_UPLOAD_GRANT_INVENTORY,
    FAMILY_UPLOAD_GRANT_INVENTORY,
    TRAVEL_UPLOAD_GRANT_INVENTORY,
    RECORD_MAP_MEDIA,
    DRIVE_OBJECT,
    LEDGER_IMAGE,
    SUPPORT_ATTACHMENT,
    EMBEDDING_DELETE
}
