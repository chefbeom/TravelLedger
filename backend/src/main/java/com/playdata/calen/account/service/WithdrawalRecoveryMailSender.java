package com.playdata.calen.account.service;

import java.time.Duration;

public interface WithdrawalRecoveryMailSender {
    void send(String email, String url, Duration validity);
}
