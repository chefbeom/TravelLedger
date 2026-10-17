package com.playdata.calen.sharing.domain.policy;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.RecordShareStatus;
import com.playdata.calen.sharing.domain.model.ShareCreationData.Existing;
import java.util.Objects;

public final class ShareCreationPolicy {
    private ShareCreationPolicy() {}

    public static void requireAllowedContent(RecordShareKind kind, boolean edited) {
        if (kind != RecordShareKind.LEDGER && edited)
            throw new BadRequestException("거래 내용 수정 공유는 가계부에서만 사용할 수 있습니다.");
    }

    public static String normalizeMemo(String memo) {
        if (memo == null) return null;
        if (memo.length() > 500) throw new BadRequestException("공유 메모는 500자까지 입력할 수 있습니다.");
        return memo.isBlank() ? null : memo.strip();
    }

    public static boolean reuse(RecordShareKind kind, Long groupId, Existing existing, boolean edited, String fingerprint) {
        boolean importedLedger = kind == RecordShareKind.LEDGER && existing.status() == RecordShareStatus.ACCEPTED;
        boolean sameGroupRequest = Objects.equals(existing.groupId(), groupId)
                && (existing.status() == RecordShareStatus.PENDING || existing.status() == RecordShareStatus.ACCEPTED);
        if (!importedLedger && !sameGroupRequest) return false;
        if (edited && !Objects.equals(fingerprint, existing.fingerprint()))
            throw new BadRequestException(importedLedger
                    ? "이미 상대방 가계부에 등록된 거래는 수정해서 다시 공유할 수 없습니다."
                    : "이미 승인 대기 중인 공유가 있습니다. 보낸 기록에서 기존 요청을 취소한 뒤 다시 공유해 주세요.");
        return true;
    }
}
