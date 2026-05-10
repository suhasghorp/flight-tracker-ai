package dev.example.flighttracker.model;

/**
 * Carries an aircraft's published origin/destination as reported by the
 * route-lookup database. Latitude/longitude are kept on each endpoint so
 * downstream code can sanity-check the route against the aircraft's
 * current position (callsigns get reused across schedules, so a stale
 * route from the database can disagree with where the plane actually is).
 */
public record RouteInfo(
        String originIata,
        String originIcao,
        String originName,
        String originCity,
        Double originLat,
        Double originLon,
        String destinationIata,
        String destinationIcao,
        String destinationName,
        String destinationCity,
        Double destinationLat,
        Double destinationLon
) {
    public String formatted() {
        return originLabel() + " → " + destinationLabel();
    }

    public String originLabel() {
        return label(originIata, originIcao, originName, originCity);
    }

    public String destinationLabel() {
        return label(destinationIata, destinationIcao, destinationName, destinationCity);
    }

    /** True only when both endpoints have coordinates we can geo-validate against. */
    public boolean hasCoordinates() {
        return originLat != null && originLon != null
                && destinationLat != null && destinationLon != null;
    }

    /**
     * Builds a "CODE (City)" label, falling back to whatever fields are present.
     * Examples: "JFK (New York)", "KORD (Chicago)", "JFK", "Chicago".
     */
    private static String label(String iata, String icao, String name, String city) {
        String code = firstNonBlank(iata, icao);
        if (code != null && city != null && !city.isBlank()) {
            return code + " (" + city + ")";
        }
        if (code != null) return code;
        if (city != null && !city.isBlank()) return city;
        if (name != null && !name.isBlank()) return name;
        return "?";
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
