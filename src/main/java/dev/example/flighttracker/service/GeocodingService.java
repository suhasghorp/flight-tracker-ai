package dev.example.flighttracker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a city + country name to latitude/longitude using the public
 * Nominatim (OpenStreetMap) endpoint. City coordinates never change, so
 * results are cached forever in-process.
 *
 * Nominatim's usage policy requires a descriptive User-Agent and limits
 * callers to ~1 request/second — fine for our interactive use.
 */
@Service
public class GeocodingService {

    private static final Logger logger = LoggerFactory.getLogger(GeocodingService.class);

    private final RestClient restClient;
    private final ConcurrentHashMap<String, Optional<double[]>> cache = new ConcurrentHashMap<>();

    public GeocodingService(
            @Value("${flight-tracker.geocoding-api.base-url:https://nominatim.openstreetmap.org}") String baseUrl) {
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setReadTimeout(1000 * 5);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", "flight-tracker-ai/1.0 (github.com/suhasghorp)")
                .build();
    }

    /**
     * Returns {lat, lon} for the given city/country, or null if not found.
     * Both inputs are required; country can be a name (e.g. "United States",
     * "Germany") or an ISO code — Nominatim accepts both.
     */
    public double[] geocode(String city, String country) {
        if (city == null || city.isBlank()) return null;

        String cacheKey = (city + "|" + (country == null ? "" : country)).toLowerCase();
        Optional<double[]> cached = cache.get(cacheKey);
        if (cached != null) return cached.orElse(null);

        Optional<double[]> result;
        try {
            String query = (country == null || country.isBlank())
                    ? city
                    : city + ", " + country;

            NominatimResult[] results = restClient.get()
                    .uri(uri -> uri.path("/search")
                            .queryParam("q", query)
                            .queryParam("format", "json")
                            .queryParam("limit", 1)
                            .build())
                    .retrieve()
                    .body(NominatimResult[].class);

            if (results == null || results.length == 0
                    || results[0].lat == null || results[0].lon == null) {
                logger.warn("No geocoding result for: {}", query);
                result = Optional.empty();
            } else {
                double lat = Double.parseDouble(results[0].lat);
                double lon = Double.parseDouble(results[0].lon);
                logger.info("Geocoded '{}' -> {}, {}", query, lat, lon);
                result = Optional.of(new double[]{lat, lon});
            }
        } catch (Exception e) {
            logger.error("Geocoding failed for {}, {}", city, country, e);
            result = Optional.empty();
        }

        cache.put(cacheKey, result);
        return result.orElse(null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NominatimResult(
            @JsonProperty("lat") String lat,
            @JsonProperty("lon") String lon) {}
}
