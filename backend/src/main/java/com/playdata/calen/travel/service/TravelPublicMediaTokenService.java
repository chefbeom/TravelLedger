package com.playdata.calen.travel.service;

import com.playdata.calen.account.security.SecuritySecretSupport;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
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
            @Value("${app.security.public-media-key:}") String secret
    ) {
        this.secretKey = SecuritySecretSupport.resolve(secret, "travel-public-media").getBytes(StandardCharsets.UTF_8);
    }

    public String issueToken(Long mediaId) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(String.valueOf(mediaId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to issue public travel media token.", exception);
        }
    }

    public boolean matches(Long mediaId, String token) {
        if (mediaId == null || token == null || token.isBlank()) {
            return false;
        }
        String expected = issueToken(mediaId);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.trim().getBytes(StandardCharsets.UTF_8)
        );
    }
}
