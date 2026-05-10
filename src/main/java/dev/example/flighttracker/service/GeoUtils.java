package dev.example.flighttracker.service;

/**
 * Spherical-earth great-circle helpers used to validate published flight
 * routes against an aircraft's live position.
 *
 * All distances are returned in nautical miles. We approximate the Earth
 * as a sphere of mean radius 3440.065 nm — accurate to within ~0.5% for
 * the comparisons we care about (filtering stale routes), which is well
 * inside our threshold tolerances.
 */
final class GeoUtils {

    /** Mean radius of the Earth in nautical miles. */
    private static final double EARTH_RADIUS_NM = 3440.065;

    private GeoUtils() {}

    /**
     * Great-circle distance between two lat/lon points using the
     * haversine formula. This is the shortest path along the surface
     * of the sphere — what an aircraft actually flies (ignoring winds
     * and routing constraints).
     */
    static double haversineNm(double lat1, double lon1, double lat2, double lon2) {
        // Convert everything to radians once up-front.
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);

        // Haversine: a = sin²(Δφ/2) + cos φ1 · cos φ2 · sin²(Δλ/2)
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                  * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);

        // c = 2 · atan2(√a, √(1−a)) — angular distance in radians.
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_NM * c;
    }

    /**
     * Initial bearing (forward azimuth) from point 1 to point 2, in radians.
     * Needed by the cross-track formula. Bearing changes along a great-circle
     * path — this gives the bearing *at* point 1.
     */
    private static double initialBearingRad(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLambda = Math.toRadians(lon2 - lon1);

        double y = Math.sin(dLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2)
                 - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);

        return Math.atan2(y, x);
    }

    /**
     * Cross-track distance: the perpendicular distance from a point to the
     * great-circle path defined by start → end. Positive/negative sign
     * indicates which side of the track; we return the absolute value
     * because we only care about how far off-course the aircraft is.
     *
     *   d_xt = asin( sin(d13/R) · sin(θ13 − θ12) ) · R
     *
     * where:
     *   d13 = distance from start to the off-track point
     *   θ13 = initial bearing from start to the off-track point
     *   θ12 = initial bearing from start to end (the track)
     */
    static double crossTrackDistanceNm(double startLat, double startLon,
                                       double endLat, double endLon,
                                       double pointLat, double pointLon) {
        // Angular distance from start to point, expressed as a fraction of
        // Earth's radius (so sin/asin work in the formula below).
        double d13OverR = haversineNm(startLat, startLon, pointLat, pointLon) / EARTH_RADIUS_NM;

        double bearing13 = initialBearingRad(startLat, startLon, pointLat, pointLon);
        double bearing12 = initialBearingRad(startLat, startLon, endLat, endLon);

        // The asin argument can drift slightly outside [-1, 1] due to
        // floating-point error for nearly-collinear points; clamp defensively.
        double sinArg = Math.sin(d13OverR) * Math.sin(bearing13 - bearing12);
        sinArg = Math.max(-1.0, Math.min(1.0, sinArg));

        return Math.abs(Math.asin(sinArg)) * EARTH_RADIUS_NM;
    }
}
