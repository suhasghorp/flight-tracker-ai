package dev.example.flighttracker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.example.flighttracker.model.Aircraft;
import dev.example.flighttracker.model.RouteInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class RouteService {

    private static final Logger logger = LoggerFactory.getLogger(RouteService.class);

    // Airline ICAO callsigns are 3 letters + digits (e.g. AAL1375, UAL1788, SWA1346).
    // Tail-number registrations like N951LA or D-EXAM don't match — they're general
    // aviation flights with no scheduled route, so the route DB will always 404.
    // Skipping them avoids wasted HTTP calls and noisy logs.
    private static final Pattern AIRLINE_CALLSIGN = Pattern.compile("^[A-Z]{3}\\d.*");

    private final RestClient restClient;
    private final ConcurrentHashMap<String, Optional<RouteInfo>> cache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    public RouteService(
            @Value("${flight-tracker.route-api.base-url:https://api.adsbdb.com/v0}") String baseUrl) {
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setReadTimeout(1000 * 5);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
    }

    public Map<String, RouteInfo> lookupRoutes(List<Aircraft> aircraft) {
        List<String> callsigns = new ArrayList<>();
        for (Aircraft ac : aircraft) {
            String c = normalize(ac.callsign());
            if (c == null || !looksLikeAirlineCallsign(c)) continue;
            if (!callsigns.contains(c)) callsigns.add(c);
        }
        if (callsigns.isEmpty()) return Map.of();

        List<CompletableFuture<Map.Entry<String, Optional<RouteInfo>>>> futures = new ArrayList<>();
        for (String callsign : callsigns) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> Map.entry(callsign, fetchRoute(callsign)),
                    executor));
        }

        Map<String, RouteInfo> result = new HashMap<>();
        for (CompletableFuture<Map.Entry<String, Optional<RouteInfo>>> f : futures) {
            try {
                Map.Entry<String, Optional<RouteInfo>> entry = f.get(8, TimeUnit.SECONDS);
                entry.getValue().ifPresent(route -> result.put(entry.getKey(), route));
            } catch (Exception e) {
                logger.debug("Route lookup future failed", e);
            }
        }
        return result;
    }

    private Optional<RouteInfo> fetchRoute(String callsign) {
        Optional<RouteInfo> cached = cache.get(callsign);
        if (cached != null) return cached;

        Optional<RouteInfo> result;
        try {
            CallsignResponse response = restClient.get()
                    .uri("/callsign/{callsign}", callsign)
                    .retrieve()
                    .body(CallsignResponse.class);

            if (response == null || response.response == null || response.response.flightroute == null) {
                result = Optional.empty();
            } else {
                FlightRoute fr = response.response.flightroute;
                Airport o = fr.origin;
                Airport d = fr.destination;
                if (o == null || d == null) {
                    result = Optional.empty();
                } else {
                    result = Optional.of(new RouteInfo(
                            o.iataCode, o.icaoCode, o.name, o.municipality, o.latitude, o.longitude,
                            d.iataCode, d.icaoCode, d.name, d.municipality, d.latitude, d.longitude));
                }
            }
        } catch (Exception e) {
            logger.debug("Route lookup failed for callsign {}: {}", callsign, e.getMessage());
            result = Optional.empty();
        }

        cache.put(callsign, result);
        return result;
    }

    private String normalize(String callsign) {
        if (callsign == null) return null;
        String trimmed = callsign.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase();
    }

    private static boolean looksLikeAirlineCallsign(String callsign) {
        return AIRLINE_CALLSIGN.matcher(callsign).matches();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CallsignResponse(@JsonProperty("response") ResponseBody response) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResponseBody(@JsonProperty("flightroute") FlightRoute flightroute) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FlightRoute(
            @JsonProperty("origin") Airport origin,
            @JsonProperty("destination") Airport destination) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Airport(
            @JsonProperty("iata_code") String iataCode,
            @JsonProperty("icao_code") String icaoCode,
            @JsonProperty("name") String name,
            @JsonProperty("municipality") String municipality,
            @JsonProperty("latitude") Double latitude,
            @JsonProperty("longitude") Double longitude) {}
}
