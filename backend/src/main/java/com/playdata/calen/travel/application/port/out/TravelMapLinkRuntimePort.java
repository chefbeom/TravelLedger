package com.playdata.calen.travel.application.port.out;

import java.time.LocalDateTime;

public interface TravelMapLinkRuntimePort {
    LocalDateTime now();
    String uniqueToken();
}
