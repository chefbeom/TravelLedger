package com.playdata.calen.account.service;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class SmtpEmailVerificationSender implements EmailVerificationSender {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.auth.email-verification.from:}")
    private String fromAddress;

    @Override
    public void send(String email, String verificationUrl, Duration validity) {
        if (!StringUtils.hasText(mailHost) || !StringUtils.hasText(fromAddress)) {
            throw new ServiceUnavailableException("이메일 인증 발송 설정이 완료되지 않았습니다.");
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(email);
            message.setSubject("TravelLedger 이메일 인증");
            message.setText("아래 링크를 열어 이메일 인증을 완료해 주세요.\n\n"
                    + verificationUrl + "\n\n"
                    + "인증 유효 시간: " + validity.toMinutes() + "분");
            mailSender.send(message);
        } catch (RuntimeException exception) {
            throw new ServiceUnavailableException("이메일 인증 메일을 보낼 수 없습니다.");
        }
    }
}
