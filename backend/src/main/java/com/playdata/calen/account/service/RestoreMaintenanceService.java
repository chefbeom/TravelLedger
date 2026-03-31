package com.playdata.calen.account.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class RestoreMaintenanceService {

    private final AtomicBoolean restoreInProgress = new AtomicBoolean(false);

    public void start() {
        restoreInProgress.set(true);
    }

    public void finish() {
        restoreInProgress.set(false);
    }

    public boolean isRestoreInProgress() {
        return restoreInProgress.get();
    }
}
