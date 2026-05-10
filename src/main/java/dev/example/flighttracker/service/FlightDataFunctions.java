package dev.example.flighttracker.service;

import dev.example.flighttracker.model.Aircraft;
import dev.example.flighttracker.model.AirportCondition;
import dev.example.flighttracker.model.RouteInfo;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class FlightDataFunctions {

    private static final int DEFAULT_CITY_SEARCH_RADIUS_NM = 20;
    private static final int DEFAULT_AIRPORT_SEARCH_RADIUS_NM = 200;

    private final FlightDataService flightDataService;
    private final RouteService routeService;
    private final GeocodingService geocodingService;
    private final ReverseGeocodingService reverseGeocodingService;
    private final AirportConditionsService airportConditionsService;

    public FlightDataFunctions(FlightDataService flightDataService,
                               RouteService routeService,
                               GeocodingService geocodingService,
                               ReverseGeocodingService reverseGeocodingService,
                               AirportConditionsService airportConditionsService) {
        this.flightDataService = flightDataService;
        this.routeService = routeService;
        this.geocodingService = geocodingService;
        this.reverseGeocodingService = reverseGeocodingService;
        this.airportConditionsService = airportConditionsService;
    }

    @Tool("Find aircraft near a specific location")
    public String findAircraftNearLocation(double latitude, double longitude, int radiusNm) {
        List<Aircraft> aircraft = flightDataService.findAircraftNear(latitude, longitude, radiusNm);
        return formatAircraftList(aircraft, "Aircraft near location");
    }

    @Tool("Find aircraft currently flying in the skies above a named city. " +
            "Use this when the user names a city and country (e.g. 'flights above Nashville, USA' " +
            "or 'what's flying over Berlin, Germany'). The search radius defaults to 20 " +
            "nautical miles around the city center; pass radiusNm only if the user specifies " +
            "a different distance (e.g. 'within 50 miles of Munich').")
    public String findAircraftAboveCity(
            @P("City name, e.g. 'Nashville'") String city,
            @P("Country name or ISO code, e.g. 'USA' or 'Germany'") String country,
            @P(value = "Search radius in nautical miles. Omit unless the user specifies a distance.",
                    required = false) Integer radiusNm) {
        double[] coords = geocodingService.geocode(city, country);
        if (coords == null) {
            return String.format("Could not find coordinates for %s, %s.", city, country);
        }
        int radius = (radiusNm != null && radiusNm > 0) ? radiusNm : DEFAULT_CITY_SEARCH_RADIUS_NM;
        List<Aircraft> aircraft = flightDataService.findAircraftNear(
                coords[0], coords[1], radius);
        return formatAircraftList(aircraft,
                String.format("Aircraft within %d nm of %s, %s", radius, city, country));
    }

    @Tool("Get current conditions (weather, forecast, and US delays) for airports " +
            "near a city. Use this when the user asks about airport conditions, weather, " +
            "delays, or runway conditions for a named city. Defaults to a 200 nm radius.")
    public String getAirportConditionsForCity(
            @P("City name, e.g. 'Nashville'") String city,
            @P("Country name or ISO code, e.g. 'USA' or 'Germany'") String country,
            @P(value = "Search radius in nautical miles. Defaults to 200.",
                    required = false) Integer radiusNm) {
        double[] coords = geocodingService.geocode(city, country);
        if (coords == null) {
            return String.format("Could not find coordinates for %s, %s.", city, country);
        }
        int radius = (radiusNm != null && radiusNm > 0) ? radiusNm : DEFAULT_AIRPORT_SEARCH_RADIUS_NM;
        List<AirportCondition> conditions = airportConditionsService.nearbyConditions(
                coords[0], coords[1], radius);
        return airportConditionsService.formatAsMarkdown(conditions,
                String.format("%s, %s", city, country));
    }

    @Tool("Find military aircraft currently in flight")
    public String findMilitaryAircraft() {
        List<Aircraft> aircraft = flightDataService.findMilitaryAircraft();
        return formatAircraftList(aircraft, "Military aircraft");
    }

    @Tool("Find aircraft by callsign")
    public String findAircraftByCallsign(String callsign) {
        List<Aircraft> aircraft = flightDataService.findByCallsign(callsign);
        return formatAircraftList(aircraft, "Aircraft with callsign " + callsign);
    }

    @Tool("Find aircraft in emergency situation")
    public String findEmergencyAircraft() {
        List<Aircraft> aircraft = flightDataService.findEmergencyAircraft();
        return formatAircraftList(aircraft, "Emergency aircraft");
    }

    private String formatAircraftList(List<Aircraft> aircraft, String title) {
        if (aircraft.isEmpty()) {
            return String.format("No %s found at this time.", title.toLowerCase());
        }

        Map<String, RouteInfo> routes = routeService.lookupRoutes(aircraft);
        // Pre-resolve every aircraft's nearest city in parallel so the
        // per-aircraft loop below hits a warm cache.
        reverseGeocodingService.prewarm(aircraft);

        StringBuilder result = new StringBuilder();
        result.append(String.format("%s (%d found):\n", title, aircraft.size()));

        aircraft.forEach(ac -> {
            // Main header for the aircraft
            result.append("\n✈️ ").append(ac.getDisplayName());
            if (ac.registration() != null && !ac.getDisplayName().equals(ac.registration())) {
                result.append(" (").append(ac.registration()).append(")");
            }
            result.append("\n");

            // Use a list to build details for cleaner formatting
            List<String> details = new java.util.ArrayList<>();

            // Type, Description, and Year
            StringBuilder typeInfo = new StringBuilder();
            if (ac.description() != null && !ac.description().isBlank()) {
                typeInfo.append(ac.description());
                if (ac.aircraftType() != null) {
                    typeInfo.append(" (").append(ac.aircraftType()).append(")");
                }
            } else if (ac.aircraftType() != null) {
                typeInfo.append(ac.aircraftType());
            }
            if (ac.year() != null && !ac.year().isBlank()) {
                typeInfo.append(", built ").append(ac.year());
            }
            if (!typeInfo.isEmpty()) {
                details.add("Type: " + typeInfo);
            }

            // Operator
            if (ac.ownerOperator() != null && !ac.ownerOperator().isBlank()) {
                details.add("Operator: " + ac.ownerOperator());
            }

            // Route (origin → destination). Suppressed when the database's
            // canonical route for this callsign clearly doesn't match where
            // the aircraft actually is — see isRoutePlausible().
            RouteInfo route = lookupRoute(routes, ac);
            if (route != null && isRoutePlausible(ac, route)) {
                details.add("Route: " + route.formatted());
            }

            // Position and Heading
            if (ac.hasLocation()) {
                String position = String.format("Position: %.4f, %.4f", ac.latitude(), ac.longitude());
                String nearestCity = reverseGeocodingService.cityNear(ac.latitude(), ac.longitude());
                if (nearestCity != null) {
                    position += " (near " + nearestCity + ")";
                }
                if (ac.heading() != null) {
                    position += String.format(" | Heading: %.0f°", ac.heading());
                }
                details.add(position);
            }

            // Altitude and Vertical Speed
            if (ac.hasAltitude()) {
                String altitude = "Altitude: " + ac.formattedAlt();
                if (ac.geomRate() != null && ac.geomRate() != 0) {
                    String direction = ac.geomRate() > 0 ? "Climbing" : "Descending";
                    altitude += String.format(" (%s at %d fpm)", direction, Math.abs(ac.geomRate()));
                }
                details.add(altitude);
            }

            // Speed
            if (ac.groundSpeed() != null) {
                details.add(String.format("Speed: %.0f kts", ac.groundSpeed()));
            }

            // Squawk code
            if (ac.squawk() != null) {
                details.add("Squawk: " + ac.squawk());
            }

            // Status (Emergency/Military)
            List<String> statuses = new java.util.ArrayList<>();
            if (ac.isEmergency()) {
                statuses.add("🚨 EMERGENCY: " + ac.emergency());
            }
            if (Boolean.TRUE.equals(ac.military())) {
                statuses.add("🪖 MILITARY");
            }
            if (!statuses.isEmpty()) {
                details.add("Status: " + String.join(" | ", statuses));
            }

            // Append all details with indentation
            for (String detail : details) {
                result.append("   - ").append(detail).append("\n");
            }
        });

        return result.toString();
    }

    private RouteInfo lookupRoute(Map<String, RouteInfo> routes, Aircraft ac) {
        if (ac.callsign() == null) return null;
        String key = ac.callsign().trim().toUpperCase();
        return key.isEmpty() ? null : routes.get(key);
    }

    /**
     * Maximum perpendicular distance (in nautical miles) the aircraft can be
     * from the great-circle path between origin and destination before we
     * consider the route stale. 150 nm comfortably covers normal weather
     * deviations and standard arrival/departure procedures.
     */
    private static final double MAX_CROSS_TRACK_NM = 150.0;

    /**
     * If the aircraft is within this radius of either endpoint, accept the
     * route even if it's well off-course — that's typical for climb-out and
     * arrival, where the plane hasn't yet joined or has already left the
     * great-circle track.
     */
    private static final double ENDPOINT_PROXIMITY_NM = 200.0;

    /**
     * Decide whether a database-published route is consistent with the
     * aircraft's current position. The route-lookup database (adsbdb.com)
     * returns a *historical* mapping for each callsign — airlines reuse the
     * same callsign across many city-pairs throughout a schedule, so the
     * cached origin/destination can disagree with what the plane is actually
     * doing today.
     *
     * Heuristic: a route is plausible if EITHER
     *   (a) the aircraft is close to one of the endpoints (taking off /
     *       landing), OR
     *   (b) the aircraft is close to the great-circle path between them
     *       (cruising along the route).
     *
     * If neither holds, the published route almost certainly belongs to a
     * different leg of the schedule and we suppress it rather than mislead
     * the user.
     */
    private boolean isRoutePlausible(Aircraft ac, RouteInfo route) {
        // Without aircraft position or both airport coordinates we can't
        // validate — fall back to trusting the database.
        if (!ac.hasLocation() || !route.hasCoordinates()) {
            return true;
        }

        double acLat = ac.latitude();
        double acLon = ac.longitude();

        // (a) Endpoint proximity — covers departure and arrival phases where
        // the great-circle approximation breaks down (the plane is climbing
        // out or being vectored onto an approach, so it's nowhere near the
        // direct line between origin and destination yet).
        double distToOrigin = GeoUtils.haversineNm(
                acLat, acLon, route.originLat(), route.originLon());
        double distToDestination = GeoUtils.haversineNm(
                acLat, acLon, route.destinationLat(), route.destinationLon());
        if (distToOrigin <= ENDPOINT_PROXIMITY_NM
                || distToDestination <= ENDPOINT_PROXIMITY_NM) {
            return true;
        }

        // (b) Cross-track distance — perpendicular offset from the great-circle
        // path. A plane truly flying ORG→DST should sit very close to this
        // line during the cruise phase; a mismatched cached route will throw
        // up huge cross-track values (different city-pair entirely).
        double crossTrack = GeoUtils.crossTrackDistanceNm(
                route.originLat(), route.originLon(),
                route.destinationLat(), route.destinationLon(),
                acLat, acLon);

        return crossTrack <= MAX_CROSS_TRACK_NM;
    }
}