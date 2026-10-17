package com.playdata.calen.travel.application.port.out;

/** Issuance for authorized read projections, separate from public download token validation. */
public interface TravelAtlasMediaTokenPort {
    String issue(Long media);
}
