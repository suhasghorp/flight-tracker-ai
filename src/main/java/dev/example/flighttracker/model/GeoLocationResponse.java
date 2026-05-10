package dev.example.flighttracker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeoLocationResponse(
        String status,
        String message,
        Double lat,
        Double lon,
        String city,
        String regionName,
        String country,
        String query
) {
    public boolean isSuccess() {
        return "success".equalsIgnoreCase(status) && lat != null && lon != null;
    }
}
