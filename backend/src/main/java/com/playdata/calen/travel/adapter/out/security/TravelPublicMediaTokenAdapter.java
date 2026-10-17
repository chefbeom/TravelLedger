package com.playdata.calen.travel.adapter.out.security;

import com.playdata.calen.travel.application.port.out.TravelPublicMediaTokenPort;
import com.playdata.calen.travel.application.port.out.TravelAtlasMediaTokenPort;
import com.playdata.calen.travel.service.TravelPublicMediaTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TravelPublicMediaTokenAdapter implements TravelPublicMediaTokenPort, TravelAtlasMediaTokenPort {
    private final TravelPublicMediaTokenService tokens;
    @Override public boolean matches(Long media, String token) { return tokens.matches(media, token); }
    @Override public String issue(Long media) { return tokens.issueToken(media); }
}
