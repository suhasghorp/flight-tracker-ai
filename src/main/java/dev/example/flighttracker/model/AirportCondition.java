package dev.example.flighttracker.model;

/**
 * Aggregated current conditions for one airport. Weather/forecast come from
 * the FAA aviationweather.gov feeds; {@code delayInfo} is populated only for
 * US airports because the FAA NAS Status API doesn't cover anywhere else.
 */
public record AirportCondition(
        String icao,
        String iata,
        String name,
        String country,
        double latitude,
        double longitude,
        double distanceNm,
        boolean isUS,
        String currentWeather,
        String rawMetar,
        String forecast,
        String delayInfo
) {}
