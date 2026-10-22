package com.playdata.calen.common.exception;

public class PreferenceConflictException extends RuntimeException {
    public PreferenceConflictException() {
        super("다른 화면에서 개인 설정이 변경되었습니다. 현재 초안을 유지한 채 최신 설정을 다시 확인해 주세요.");
    }
}
