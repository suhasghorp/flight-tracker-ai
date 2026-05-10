package dev.example.flighttracker.service;

import dev.example.flighttracker.model.GeoLocationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GeoLocationService {

    private static final Logger logger = LoggerFactory.getLogger(GeoLocationService.class);

    private final RestClient restClient;

    public GeoLocationService(
            @Value("${flight-tracker.geo-api.base-url:http://ip-api.com/json}") String baseUrl) {
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setReadTimeout(1000 * 5);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
    }

    public GeoLocationResponse lookup(String ip) {
        if (ip == null || ip.isBlank() || isLocalAddress(ip)) {
            logger.debug("Skipping geolocation for local/empty ip: {}", ip);
            return null;
        }
        try {
            GeoLocationResponse response = restClient.get()
                    .uri("/{ip}", ip)
                    .retrieve()
                    .body(GeoLocationResponse.class);

            if (response == null || !response.isSuccess()) {
                logger.warn("Geolocation lookup unsuccessful for ip {}: {}", ip,
                        response != null ? response.message() : "null response");
                return null;
            }
            return response;
        } catch (Exception e) {
            logger.error("Failed to resolve geolocation for ip: {}", ip, e);
            return null;
        }
    }

    private boolean isLocalAddress(String ip) {
        return ip.startsWith("127.")
                || ip.startsWith("10.")
                || ip.startsWith("192.168.")
                || ip.startsWith("172.16.")
                || ip.equals("0:0:0:0:0:0:0:1")
                || ip.equals("::1")
                || ip.equalsIgnoreCase("localhost");
    }
}
