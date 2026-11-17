package com.playdata.calen.ledger.ocr;

import com.playdata.calen.account.service.AppUserService;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Category creation by an asynchronous OCR result must serialize with account deactivation. */
@Service
@RequiredArgsConstructor
public class LedgerOcrCategoryWriteFence {
    private final AppUserService users;

    @Transactional
    public <T> T withActiveOwner(Long userId, Supplier<T> action) {
        users.getRequiredUserForWrite(userId);
        return action.get();
    }
}
