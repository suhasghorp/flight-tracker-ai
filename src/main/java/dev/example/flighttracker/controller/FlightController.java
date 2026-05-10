package dev.example.flighttracker.controller;

import dev.example.flighttracker.model.AirportCondition;
import dev.example.flighttracker.model.GeoLocationResponse;
import dev.example.flighttracker.service.AirportConditionsService;
import dev.example.flighttracker.service.FlightAssistantService;
import dev.example.flighttracker.service.GeoLocationService;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Refill;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/*
PUBLIC_IP=$(curl -s -4 ifconfig.me)
curl -X POST http://localhost:8080/api/aviation/ask   -H "Content-Type: text/plain"   -H "X-Forwarded-For: $PUBLIC_IP"   -d "Are there any military aircraft visible right now?"
curl -X POST http://localhost:8080/api/aviation/ask   -H "Content-Type: text/plain"   -H "X-Forwarded-For: $PUBLIC_IP"   -d "What commercial flights are within 20 nautical miles of my current location?"

 # Build the OCI image (lands in your local Docker daemon)
  ./mvnw spring-boot:build-image -DskipTests

  # Run it
  docker run -p 8080:8080 flight-tracker-ai:0.0.1-SNAPSHOT

  # Override env vars at runtime (e.g. inject API keys instead of baking them in)
  see /home/suhasghorp/run-flight-tracker.sh
  this shell script has the env vars set for you

    # change the version to 0.0.x in pom.xml
    # build docker image and push to docker hub
     ./mvnw spring-boot:build-image -Ddocker.image.name=suhasghorp/flight-tracker-ai
  docker push suhasghorp/flight-tracker-ai:0.0.x-SNAPSHOT
    in azure, application -> containers
    change image/tag , it will create a new revision
    restart container

*/

@RestController
@RequestMapping("/api/aviation")
public class FlightController {

    private static final Logger logger = LoggerFactory.getLogger(FlightController.class);

    private static final int AIRPORTS_RADIUS_NM = 200;

    private final FlightAssistantService assistantService;
    private final GeoLocationService geoLocationService;
    private final AirportConditionsService airportConditionsService;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public FlightController(FlightAssistantService assistantService,
                            GeoLocationService geoLocationService,
                            AirportConditionsService airportConditionsService) {
        this.assistantService = assistantService;
        this.geoLocationService = geoLocationService;
        this.airportConditionsService = airportConditionsService;
    }

    @PostMapping("/ask")
    public ResponseEntity<String> askQuestion(
            @RequestBody @NotBlank String question,
            @RequestHeader(value = "X-Forwarded-For", required = false) String xForwardedFor,
            jakarta.servlet.http.HttpServletRequest request) {

        // Use X-Forwarded-For if present (handling multiple IPs if it's a chain),
        // otherwise fall back to the remote address.
        String clientIp = (xForwardedFor != null && !xForwardedFor.isEmpty())
                ? xForwardedFor.split(",")[0].trim()
                : request.getRemoteAddr();

        // Rate limiting
        Bucket bucket = buckets.computeIfAbsent(clientIp, this::createBucket);
        if (!bucket.tryConsume(1)) {
            return ResponseEntity.status(429).body("Rate limit exceeded. Please try again later.");
        }

        try {
            logger.info("Processing aviation query from {}: {}", clientIp, question);
            String response = assistantService.processQuery(question, clientIp);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error processing query: {}", question, e);
            return ResponseEntity.status(500)
                    .body("Sorry, I encountered an error processing your question. Please try again.");
        }
    }

    @GetMapping("/airports")
    public ResponseEntity<String> nearbyAirports(
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lon", required = false) Double lon,
            @RequestHeader(value = "X-Forwarded-For", required = false) String xForwardedFor,
            jakarta.servlet.http.HttpServletRequest request) {

        String clientIp = (xForwardedFor != null && !xForwardedFor.isEmpty())
                ? xForwardedFor.split(",")[0].trim()
                : request.getRemoteAddr();

        Bucket bucket = buckets.computeIfAbsent(clientIp, this::createBucket);
        if (!bucket.tryConsume(1)) {
            return ResponseEntity.status(429).body("Rate limit exceeded. Please try again later.");
        }

        // Prefer caller-supplied coordinates (typically from the browser's
        // Geolocation API). Fall back to IP-based lookup only when none given.
        double resolvedLat;
        double resolvedLon;
        String label;
        if (lat != null && lon != null) {
            resolvedLat = lat;
            resolvedLon = lon;
            label = String.format("your location (%.4f, %.4f)", lat, lon);
        } else {
            GeoLocationResponse loc = geoLocationService.lookup(clientIp);
            if (loc == null) {
                return ResponseEntity.ok("Could not determine your location. " +
                        "Allow location access in your browser, or ask the assistant: " +
                        "\"airport conditions in <city>, <country>\".");
            }
            resolvedLat = loc.lat();
            resolvedLon = loc.lon();
            label = String.format("%s, %s",
                    loc.city() != null ? loc.city() : "your location",
                    loc.country() != null ? loc.country() : "");
        }

        try {
            List<AirportCondition> conditions = airportConditionsService.nearbyConditions(
                    resolvedLat, resolvedLon, AIRPORTS_RADIUS_NM);
            return ResponseEntity.ok(airportConditionsService.formatAsMarkdown(conditions, label));
        } catch (Exception e) {
            logger.error("Failed to fetch airport conditions for {}", clientIp, e);
            return ResponseEntity.status(500).body("Failed to fetch airport conditions. Please try again.");
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Aviation AI Assistant is ready for takeoff! ✈️");
    }

    @GetMapping("/examples")
    public ResponseEntity<String> examples() {
        String exampleQueries = """
        ## Try These Example Queries:
        
        - "What military aircraft are currently flying?"
        - "Show me flights near Toronto Pearson International Airport within 20 nautical miles"
        - "Are there any emergency aircraft right now?"
        - "Find flight BA123"
        - "What's flying within 30 nautical miles of New York?"
        
        **Tip:** Be specific about locations and use major airport names for best results!
        """;
        return ResponseEntity.ok(exampleQueries);
    }

    private Bucket createBucket(String key) {
        Bandwidth limit = Bandwidth.classic(60, Refill.intervally(60, Duration.ofMinutes(1)));
        return Bucket4j.builder()
                .addLimit(limit)
                .build();
    }
}
