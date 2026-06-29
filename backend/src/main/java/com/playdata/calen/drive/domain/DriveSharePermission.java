package com.playdata.calen.drive.domain;

public enum DriveSharePermission {
    VIEW,
    DOWNLOAD,
    EDIT;

    public boolean canDownload() {
        return this == DOWNLOAD || this == EDIT;
    }
}