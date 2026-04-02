package com.playdata.calen.travel.dto;

public record TravelReverseGeocodeResponse(
        String country,
        String region,
        String placeName
) {

    public static TravelReverseGeocodeResponse empty() {
        return new TravelReverseGeocodeResponse("", "", "");
    }

    public boolean isEmpty() {
        return country.isBlank() && region.isBlank() && placeName.isBlank();
    }
}
