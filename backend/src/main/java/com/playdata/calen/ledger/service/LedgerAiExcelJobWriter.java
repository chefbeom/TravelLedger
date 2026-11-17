package com.playdata.calen.ledger.service;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.exception.TooManyRequestsException;
import com.playdata.calen.ledger.domain.LedgerExcelAnalysisJob;
import com.playdata.calen.ledger.repository.LedgerExcelAnalysisJobRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists admission while holding the same account lock as withdrawal. */
@Service
@RequiredArgsConstructor
public class LedgerAiExcelJobWriter {
    private final AppUserRepository users;
    private final LedgerExcelAnalysisJobRepository jobs;

    @Transactional
    public LedgerExcelAnalysisJob create(Long ownerId, String inputJson) {
        users.findActiveForPreferenceUpdate(ownerId)
                .orElseThrow(() -> new NotFoundException("사용자 계정을 찾을 수 없습니다."));
        if (jobs.countByOwnerIdAndStatus(ownerId, "PROCESSING") >= 5) {
            throw new TooManyRequestsException("진행 중인 Excel 분석이 많습니다. 잠시 후 다시 시도해 주세요.", 30);
        }
        LedgerExcelAnalysisJob job = new LedgerExcelAnalysisJob();
        job.setId(UUID.randomUUID().toString());
        job.setOwnerId(ownerId);
        job.setInputJson(inputJson);
        return jobs.saveAndFlush(job);
    }
}
