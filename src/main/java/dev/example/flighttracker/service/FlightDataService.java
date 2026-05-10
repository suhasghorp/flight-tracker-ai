package dev.example.flighttracker.service;

import dev.example.flighttracker.model.Aircraft;
import dev.example.flighttracker.model.FlightDataResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.HttpComponentsClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Service
public class FlightDataService {

    private static final Logger logger = LoggerFactory.getLogger(FlightDataService.class);

    private final RestClient webClient;

    public FlightDataService(@Value("${flight-tracker.adsb-api.base-url}") String baseUrl,
                             @Value("${flight-tracker.adsb-api.timeout}") Duration timeout) {
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setReadTimeout(1000 * 10);

        this.webClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
    }

    public List<Aircraft> findMilitaryAircraft() {
        logger.debug("Fetching military aircraft data");
        return makeRequest("/mil");
    }

    public List<Aircraft> findAircraftNear(double latitude, double longitude, int radiusNm) {
        logger.debug("Fetching aircraft near {}, {} within {}nm", latitude, longitude, radiusNm);
        String path = String.format("/lat/%.4f/lon/%.4f/dist/%d", latitude, longitude, radiusNm);
        return makeRequest(path);
    }

    public List<Aircraft> findByCallsign(String callsign) {
        logger.debug("Fetching aircraft with callsign: {}", callsign);
        return makeRequest("/callsign/" + callsign.toUpperCase());
    }

    public List<Aircraft> findEmergencyAircraft() {
        logger.debug("Fetching emergency aircraft (squawk 7700)");
        return makeRequest("/sqk/7700");
    }

    private List<Aircraft> makeRequest(String path) {
        try {
            System.out.println("path: " + path);
            FlightDataResponse response = webClient.get()
                    .uri(path)
                    .retrieve()
                    .body(FlightDataResponse.class);

            return response != null ? response.getAircraft() : List.of();
        } catch (Exception e) {
            logger.error("Failed to fetch flight data from path: {}", path, e);
            return List.of();
        }
    }
}