package com.playdata.calen.account.service;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class SmtpWithdrawalRecoveryMailSender implements WithdrawalRecoveryMailSender {
    private final JavaMailSender sender;
    @Value("${spring.mail.host:}") private String mailHost;
    @Value("${app.auth.email-verification.from:}") private String from;

    @Override public void send(String email, String url, Duration validity) {
        if (!StringUtils.hasText(mailHost) || !StringUtils.hasText(from)) {
            throw new ServiceUnavailableException("이메일 인증 발송 설정이 완료되지 않았습니다.");
        }
        try {
            var message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(email);
            message.setSubject("TravelLedger 회원 탈퇴 이메일 확인");
            message.setText("카카오 로그인에 접근할 수 없는 계정의 탈퇴 이메일 확인 요청입니다.\n"
                    + "본인이 요청하지 않았다면 이 메일을 무시하세요. 이 링크만으로 계정이 삭제되지는 않습니다.\n\n"
                    + url + "\n\n유효 시간: " + validity.toMinutes() + "분");
            sender.send(message);
        } catch (RuntimeException exception) {
            throw new ServiceUnavailableException("이메일 인증 메일을 보낼 수 없습니다.");
        }
    }
}
