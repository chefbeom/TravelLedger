package com.playdata.calen.travel.application.port.out;

public interface TravelPublicMediaTokenPort {
    boolean matches(Long media, String token);
}
