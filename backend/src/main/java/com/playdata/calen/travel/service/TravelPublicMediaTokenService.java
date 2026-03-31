package com.playdata.calen.travel.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TravelPublicMediaTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] secretKey;

    public TravelPublicMediaTokenService(
            @Value("${app.security.public-media-key:${JWT_KEY:calen-public-media-key-change-me}}") String secret
    ) {
        this.secretKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issueToken(Long mediaId) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(String.valueOf(mediaId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("공개 여행 사진 토큰을 생성할 수 없습니다.", exception);
        }
    }

    public boolean matches(Long mediaId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return issueToken(mediaId).equals(token.trim());
    }
}
