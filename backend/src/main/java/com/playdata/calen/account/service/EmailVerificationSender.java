package com.playdata.calen.account.service;

import java.time.Duration;

public interface EmailVerificationSender {

    void send(String email, String verificationUrl, Duration validity);
}
