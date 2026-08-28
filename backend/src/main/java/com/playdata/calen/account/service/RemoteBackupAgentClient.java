package com.playdata.calen.account.service;

import com.playdata.calen.account.dto.AdminBackupFileResponse;
import java.util.List;

public interface RemoteBackupAgentClient {

    AdminBackupFileResponse createMariaDbBackup();

    AdminBackupFileResponse createMinioBackup();

    List<AdminBackupFileResponse> listBackups(String service);

    void restoreMariaDb(String fileName);
}
