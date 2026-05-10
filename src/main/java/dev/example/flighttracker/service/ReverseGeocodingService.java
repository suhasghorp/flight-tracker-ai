package dev.example.flighttracker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.example.flighttracker.model.Aircraft;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Reverse-geocodes aircraft positions to a nearby place name (locality
 * preferred, then city, then state). Uses BigDataCloud's free
 * /data/reverse-geocode-client endpoint, which is designed for high-volume
 * client-side use without an API key.
 *
 * Results are cached on a coarse coordinate grid (~1 km) so neighbouring
 * aircraft share lookups, and a fixed thread pool fans out parallel
 * requests when pre-warming a batch.
 */
@Service
public class ReverseGeocodingService {

    private static final Logger logger = LoggerFactory.getLogger(ReverseGeocodingService.class);

    private final RestClient restClient;
    private final ConcurrentHashMap<String, Optional<String>> cache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    public ReverseGeocodingService(
            @Value("${flight-tracker.reverse-geocoding-api.base-url:https://api-bdc.io}") String baseUrl) {
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setReadTimeout(1000 * 5);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Pre-resolve every aircraft position in parallel, populating the cache.
     * Subsequent calls to {@link #cityNear(double, double)} will hit the
     * cache and return immediately.
     */
    public void prewarm(List<Aircraft> aircraft) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Aircraft ac : aircraft) {
            if (!ac.hasLocation()) continue;
            double lat = ac.latitude();
            double lon = ac.longitude();
            String key = gridKey(lat, lon);
            if (cache.containsKey(key)) continue;
            futures.add(CompletableFuture.runAsync(() -> fetch(lat, lon), executor));
        }
        for (CompletableFuture<Void> f : futures) {
            try {
                f.get(6, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.debug("Reverse-geocode prewarm task failed", e);
            }
        }
    }

    /** Returns the closest city/locality name for the given coordinates, or null. */
    public String cityNear(double lat, double lon) {
        Optional<String> cached = cache.get(gridKey(lat, lon));
        if (cached != null) return cached.orElse(null);
        return fetch(lat, lon).orElse(null);
    }

    private Optional<String> fetch(double lat, double lon) {
        String key = gridKey(lat, lon);
        Optional<String> existing = cache.get(key);
        if (existing != null) return existing;

        Optional<String> result;
        try {
            ReverseResponse response = restClient.get()
                    .uri(uri -> uri.path("/data/reverse-geocode-client")
                            .queryParam("latitude", lat)
                            .queryParam("longitude", lon)
                            .queryParam("localityLanguage", "en")
                            .build())
                    .retrieve()
                    .body(ReverseResponse.class);

            result = Optional.ofNullable(pickName(response));
        } catch (Exception e) {
            logger.debug("Reverse geocode failed for {},{}: {}", lat, lon, e.getMessage());
            result = Optional.empty();
        }

        cache.put(key, result);
        return result;
    }

    /**
     * Builds a "City, State, Country" place label from the response.
     * Locality (most granular) is preferred over city. Region and country
     * are added when present and not redundant. Returns null when the
     * response carries nothing useful (e.g. mid-ocean lookups).
     *
     * Examples:
     *   "Healy, Alaska, US"
     *   "Berlin, Berlin, DE"  → de-duplicated to "Berlin, DE"
     *   "Frankfurt, Hesse, DE"
     *   "DE"                  → suppressed (country alone is too vague)
     */
    private String pickName(ReverseResponse r) {
        if (r == null) return null;
        String place = notBlank(r.locality) ? r.locality
                : notBlank(r.city) ? r.city : null;
        String region = notBlank(r.principalSubdivision) ? r.principalSubdivision : null;
        String country = notBlank(r.countryCode) ? r.countryCode : null;

        List<String> parts = new ArrayList<>(3);
        if (place != null) parts.add(place);
        // Skip the region when it duplicates the place (e.g. "Berlin, Berlin").
        if (region != null && !region.equalsIgnoreCase(place)) parts.add(region);
        if (country != null) parts.add(country);

        // Country alone isn't location-specific enough to be useful in a
        // flight context — omit the "near ..." suffix entirely in that case.
        if (parts.size() < 2 && place == null) return null;

        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Quantise coordinates to ~1 km grid cells (0.01° lat/lon ≈ 1 km).
     * Aircraft within the same cell will share a single API call —
     * important when a flock of planes sits over the same metro area.
     */
    private String gridKey(double lat, double lon) {
        return String.format("%.2f,%.2f", lat, lon);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReverseResponse(
            @JsonProperty("city") String city,
            @JsonProperty("locality") String locality,
            @JsonProperty("principalSubdivision") String principalSubdivision,
            @JsonProperty("countryCode") String countryCode,
            @JsonProperty("countryName") String countryName) {}
}
