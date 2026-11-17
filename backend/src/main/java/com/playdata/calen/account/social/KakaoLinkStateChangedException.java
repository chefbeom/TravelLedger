package com.playdata.calen.account.social;

import com.playdata.calen.common.exception.BadRequestException;

/** The security proof must be restarted after the account's credentials change. */
public class KakaoLinkStateChangedException extends BadRequestException {
    public KakaoLinkStateChangedException() {
        super("계정 보안 정보가 변경되었습니다. 카카오 연결을 다시 시작해 주세요.");
    }
}
