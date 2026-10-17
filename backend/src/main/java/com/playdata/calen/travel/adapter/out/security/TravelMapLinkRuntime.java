package com.playdata.calen.travel.adapter.out.security;

import com.playdata.calen.travel.application.port.out.TravelMapLinkRuntimePort;
import com.playdata.calen.travel.repository.TravelMapShareLinkRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class TravelMapLinkRuntime implements TravelMapLinkRuntimePort {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final TravelMapShareLinkRepository links;
    @Override public LocalDateTime now() { return LocalDateTime.now(); }
    @Override public String uniqueToken() {
        byte[] bytes = new byte[24]; String token;
        do { RANDOM.nextBytes(bytes); token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        while (links.existsByToken(token));
        return token;
    }
}
