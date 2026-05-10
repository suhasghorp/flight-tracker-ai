package dev.example.flighttracker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.example.flighttracker.model.AirportCondition;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Aggregates airport conditions from three free public sources:
 *
 *   - Current weather (METAR) via aviationweather.gov bbox query
 *   - Terminal forecast (TAF) via aviationweather.gov per-station query
 *   - US delays / ground stops / closures via FAA NAS Status (XML)
 *
 * Only the closest {@value #MAX_AIRPORTS_RETURNED} airports are kept to
 * keep response size bounded and TAF fan-out predictable.
 */
@Service
public class AirportConditionsService {

    private static final Logger logger = LoggerFactory.getLogger(AirportConditionsService.class);

    /** 1 degree of latitude is ~60 nm; longitude scales by cos(lat). */
    private static final double NM_PER_DEGREE_LAT = 60.0;

    /**
     * Cap on how many airports we return per request. Set high enough that
     * a normal "within 200 nm" query feels uncapped, but low enough to keep
     * the formatted response and the TAF fan-out manageable.
     */
    private static final int MAX_AIRPORTS_RETURNED = 50;

    private final RestClient awcClient;
    private final RestClient faaClient;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    public AirportConditionsService(
            @Value("${flight-tracker.aviation-weather-api.base-url:https://aviationweather.gov/api}") String awcBaseUrl,
            @Value("${flight-tracker.faa-nas-api.base-url:https://nasstatus.faa.gov/api}") String faaBaseUrl) {
        HttpComponentsClientHttpRequestFactory rf = new HttpComponentsClientHttpRequestFactory();
        rf.setReadTimeout(1000 * 8);

        this.awcClient = RestClient.builder().requestFactory(rf).baseUrl(awcBaseUrl).build();
        this.faaClient = RestClient.builder().requestFactory(rf).baseUrl(faaBaseUrl).build();
    }

    public List<AirportCondition> nearbyConditions(double lat, double lon, int radiusNm) {
        // 1. Use stationinfo as the airport directory — it returns every
        //    weather station (airports + helipads) in the bbox regardless
        //    of whether they have a *current* METAR observation. This is
        //    why we no longer key the list off the METAR query: airports
        //    that simply aren't reporting right now still show up.
        List<StationInfo> stations = fetchStationInfoBbox(lat, lon, radiusNm);
        if (stations.isEmpty()) return List.of();

        // 2. Compute true great-circle distance and filter; bbox is square,
        //    radius is round, so corner stations may be > radius away.
        List<StationInfo> nearby = new ArrayList<>();
        for (StationInfo s : stations) {
            if (s.icaoId == null || s.lat == null || s.lon == null) continue;
            double d = GeoUtils.haversineNm(lat, lon, s.lat, s.lon);
            if (d <= radiusNm) {
                s.distanceNm = d;
                nearby.add(s);
            }
        }
        nearby.sort(Comparator.comparingDouble(s -> s.distanceNm));
        boolean capped = nearby.size() > MAX_AIRPORTS_RETURNED;
        if (capped) nearby = nearby.subList(0, MAX_AIRPORTS_RETURNED);

        // 3. Enrich in parallel — METAR bbox is one call covering all,
        //    TAF is per-station (only for those that publish one), and the
        //    FAA NAS feed is one bulk call covering the entire US.
        Map<String, MetarStation> metarByIcao = fetchMetarBbox(lat, lon, radiusNm).stream()
                .filter(m -> m.icaoId != null)
                .collect(java.util.stream.Collectors.toMap(m -> m.icaoId, m -> m, (a, b) -> a));
        Map<String, String> tafByIcao = fetchTafsParallel(nearby);
        Map<String, String> delaysByIata = fetchFaaDelays();

        List<AirportCondition> result = new ArrayList<>(nearby.size());
        for (StationInfo s : nearby) {
            MetarStation metar = metarByIcao.get(s.icaoId);
            String iata = s.iataId != null ? s.iataId : icaoToIata(s.icaoId);
            boolean us = "US".equalsIgnoreCase(s.country);
            String delay = us ? delaysByIata.getOrDefault(iata, "✅ No delays reported") : null;
            result.add(new AirportCondition(
                    s.icaoId,
                    iata,
                    s.site,
                    s.country,
                    s.lat,
                    s.lon,
                    s.distanceNm,
                    us,
                    metar != null ? summariseMetar(metar) : "No current observations",
                    metar != null ? metar.rawOb : null,
                    summariseTaf(tafByIcao.get(s.icaoId)),
                    delay));
        }
        return result;
    }

    // ---------------- Station directory ----------------

    private List<StationInfo> fetchStationInfoBbox(double lat, double lon, int radiusNm) {
        String bbox = bboxFor(lat, lon, radiusNm);
        try {
            StationInfo[] response = awcClient.get()
                    .uri(uri -> uri.path("/data/stationinfo")
                            .queryParam("bbox", bbox)
                            .queryParam("format", "json")
                            .build())
                    .retrieve()
                    .body(StationInfo[].class);
            return response == null ? List.of() : List.of(response);
        } catch (Exception e) {
            logger.error("Stationinfo bbox fetch failed for bbox={}", bbox, e);
            return List.of();
        }
    }

    // ---------------- METAR ----------------

    private List<MetarStation> fetchMetarBbox(double lat, double lon, int radiusNm) {
        String bbox = bboxFor(lat, lon, radiusNm);
        try {
            MetarStation[] response = awcClient.get()
                    .uri(uri -> uri.path("/data/metar")
                            .queryParam("bbox", bbox)
                            .queryParam("format", "json")
                            .build())
                    .retrieve()
                    .body(MetarStation[].class);
            return response == null ? List.of() : List.of(response);
        } catch (Exception e) {
            logger.error("METAR bbox fetch failed for bbox={}", bbox, e);
            return List.of();
        }
    }

    private String summariseMetar(MetarStation s) {
        StringBuilder sb = new StringBuilder();
        if (s.wdir != null && s.wspd != null) {
            sb.append("Wind ").append(s.wdir).append("° at ").append(s.wspd).append(" kts");
        } else if (s.wspd != null) {
            sb.append("Wind ").append(s.wspd).append(" kts");
        }
        if (s.visib != null) appendSep(sb).append("Vis ").append(s.visib).append(" SM");
        if (s.cover != null) appendSep(sb).append(skyCoverLabel(s.cover));
        if (s.temp != null) appendSep(sb).append(String.format("%.0f°C", s.temp));
        if (s.wxString != null && !s.wxString.isBlank()) appendSep(sb).append(s.wxString);
        return sb.length() == 0 ? "(no current weather)" : sb.toString();
    }

    private static StringBuilder appendSep(StringBuilder sb) {
        if (sb.length() > 0) sb.append(", ");
        return sb;
    }

    private static String skyCoverLabel(String cover) {
        return switch (cover.toUpperCase()) {
            case "CLR", "SKC" -> "Clear";
            case "FEW" -> "Few clouds";
            case "SCT" -> "Scattered";
            case "BKN" -> "Broken";
            case "OVC" -> "Overcast";
            default -> cover;
        };
    }

    // ---------------- TAF ----------------

    private Map<String, String> fetchTafsParallel(List<StationInfo> stations) {
        // Concurrent map so each future can publish its result directly.
        // Many airports have no TAF, and those should simply be absent from
        // the result map. We also skip airports whose siteType doesn't
        // include "TAF" — saves wasted HTTP calls that would 404 anyway.
        Map<String, String> result = new java.util.concurrent.ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (StationInfo s : stations) {
            if (s.icaoId == null) continue;
            if (s.siteType != null && !s.siteType.contains("TAF")) continue;
            futures.add(CompletableFuture.runAsync(() -> {
                String taf = fetchTaf(s.icaoId);
                if (taf != null) result.put(s.icaoId, taf);
            }, executor));
        }
        for (CompletableFuture<Void> f : futures) {
            try {
                f.get(8, TimeUnit.SECONDS);
            } catch (Exception ex) {
                logger.debug("TAF future failed", ex);
            }
        }
        return result;
    }

    private String fetchTaf(String icao) {
        try {
            TafEntry[] response = awcClient.get()
                    .uri(uri -> uri.path("/data/taf")
                            .queryParam("ids", icao)
                            .queryParam("format", "json")
                            .build())
                    .retrieve()
                    .body(TafEntry[].class);
            if (response == null || response.length == 0) return null;
            return response[0].rawTAF;
        } catch (Exception e) {
            logger.debug("TAF fetch failed for {}: {}", icao, e.getMessage());
            return null;
        }
    }

    private String summariseTaf(String rawTaf) {
        if (rawTaf == null || rawTaf.isBlank()) return null;
        // Strip the TAF header (report type, station, issue time, validity)
        // before decoding — the decoder works on the forecast body.
        String trimmed = rawTaf.replaceFirst("^TAF\\s+(AMD\\s+|COR\\s+)?\\S+\\s+\\d{6}Z\\s+\\d{4}/\\d{4}\\s+", "");
        return WeatherDecoder.decodeTaf(trimmed.trim());
    }

    // ---------------- FAA NAS Status (delays) ----------------

    /** Returns IATA -> human-readable delay description for every airport currently impacted. */
    private Map<String, String> fetchFaaDelays() {
        try {
            String xml = faaClient.get()
                    .uri("/airport-status-information")
                    .accept(MediaType.APPLICATION_XML)
                    .retrieve()
                    .body(String.class);
            if (xml == null || xml.isBlank()) return Map.of();
            return parseFaaXml(xml);
        } catch (Exception e) {
            logger.error("FAA NAS Status fetch failed", e);
            return Map.of();
        }
    }

    private Map<String, String> parseFaaXml(String xml) {
        Map<String, String> result = new HashMap<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // Defensive: disable external entity resolution to avoid XXE.
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            // Three categories the NAS feed publishes; each lives under its own <Delay_type>.
            for (Node delayType : nodeList(doc.getElementsByTagName("Delay_type"))) {
                Element el = (Element) delayType;
                String name = textOf(el, "Name");
                if ("Ground Stop Programs".equalsIgnoreCase(name)) {
                    for (Node program : nodeList(el.getElementsByTagName("Program"))) {
                        String arpt = textOf((Element) program, "ARPT");
                        String reason = textOf((Element) program, "Reason");
                        String end = textOf((Element) program, "End_Time");
                        if (arpt != null) {
                            result.merge(arpt,
                                    String.format("🛑 Ground stop (%s) until %s", reason, end),
                                    (a, b) -> a + "; " + b);
                        }
                    }
                } else if ("Ground Delay Programs".equalsIgnoreCase(name)) {
                    for (Node gd : nodeList(el.getElementsByTagName("Ground_Delay"))) {
                        String arpt = textOf((Element) gd, "ARPT");
                        String reason = textOf((Element) gd, "Reason");
                        String avg = textOf((Element) gd, "Avg");
                        String max = textOf((Element) gd, "Max");
                        if (arpt != null) {
                            result.merge(arpt,
                                    String.format("⏳ Ground delay (%s): avg %s, max %s", reason, avg, max),
                                    (a, b) -> a + "; " + b);
                        }
                    }
                } else if ("Airport Closures".equalsIgnoreCase(name)) {
                    for (Node ap : nodeList(el.getElementsByTagName("Airport"))) {
                        String arpt = textOf((Element) ap, "ARPT");
                        String reopen = textOf((Element) ap, "Reopen");
                        if (arpt != null) {
                            result.merge(arpt,
                                    String.format("⛔ Closed, reopens %s", reopen),
                                    (a, b) -> a + "; " + b);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to parse FAA NAS XML", e);
        }
        return result;
    }

    private static List<Node> nodeList(NodeList nl) {
        List<Node> list = new ArrayList<>(nl.getLength());
        for (int i = 0; i < nl.getLength(); i++) list.add(nl.item(i));
        return list;
    }

    private static String textOf(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        String text = nl.item(0).getTextContent();
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    // ---------------- helpers ----------------

    /**
     * AWC bbox parameter order: minLat,minLon,maxLat,maxLon. Longitude
     * scaling uses cos(lat) so the bbox stays roughly circular at any
     * latitude (degenerate near the poles, hence the 0.1 floor).
     */
    private String bboxFor(double lat, double lon, int radiusNm) {
        double dLat = radiusNm / NM_PER_DEGREE_LAT;
        double dLon = radiusNm / (NM_PER_DEGREE_LAT * Math.max(0.1, Math.cos(Math.toRadians(lat))));
        return String.format("%.4f,%.4f,%.4f,%.4f",
                lat - dLat, lon - dLon, lat + dLat, lon + dLon);
    }

    /** For US-style "K####" ICAO codes the IATA is the trailing 3 chars. */
    private String icaoToIata(String icao) {
        if (icao == null || !icao.startsWith("K") || icao.length() != 4) return null;
        return icao.substring(1);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /**
     * Renders a list of conditions as Markdown for direct display in the chat UI.
     * Includes the heads-up about delay coverage when any non-US airport is in the list.
     */
    public String formatAsMarkdown(List<AirportCondition> conditions, String locationLabel) {
        if (conditions.isEmpty()) {
            return "No reporting airports found near " + locationLabel + ".";
        }

        boolean anyNonUS = conditions.stream().anyMatch(c -> !c.isUS());
        StringBuilder sb = new StringBuilder();
        sb.append("## Airport Conditions near ").append(locationLabel).append("\n\n");

        for (AirportCondition c : conditions) {
            sb.append("### ✈️ ").append(c.icao());
            if (c.iata() != null) sb.append(" (").append(c.iata()).append(")");
            sb.append(" — ").append(c.name() == null ? "" : c.name());
            sb.append(" — ").append(String.format("%.0f nm", c.distanceNm())).append("\n\n");

            sb.append("- **Current:** ").append(c.currentWeather()).append("\n");
            // Full decoded METAR — adds detail (altimeter, dewpoint, all
            // cloud layers) on top of the brief Current line. Raw METAR
            // string is omitted because it's not human-readable.
            if (c.rawMetar() != null) {
                String decoded = WeatherDecoder.decodeMetar(c.rawMetar());
                if (decoded != null && !decoded.isBlank()) {
                    sb.append("- **Conditions:** ").append(decoded).append("\n");
                }
            }
            if (c.forecast() != null) {
                sb.append("- **Forecast:**").append(c.forecast()).append("\n");
            }
            if (c.isUS()) {
                sb.append("- **Delays:** ").append(c.delayInfo()).append("\n");
            } else {
                sb.append("- **Delays:** _not available outside the US_\n");
            }
            sb.append("\n");
        }

        if (anyNonUS) {
            sb.append("> ℹ️ Delay information is only available for US airports ")
                    .append("(via the FAA NAS Status feed).\n");
        }
        return sb.toString();
    }

    // ---------------- DTOs ----------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class MetarStation {
        @JsonProperty("icaoId") public String icaoId;
        @JsonProperty("name") public String name;
        @JsonProperty("lat") public Double lat;
        @JsonProperty("lon") public Double lon;
        @JsonProperty("temp") public Double temp;
        @JsonProperty("wdir") public String wdir;
        @JsonProperty("wspd") public Integer wspd;
        @JsonProperty("visib") public Object visib;
        @JsonProperty("cover") public String cover;
        @JsonProperty("wxString") public String wxString;
        @JsonProperty("rawOb") public String rawOb;
    }

    /**
     * Entry from the AWC stationinfo endpoint. {@code siteType} is an array
     * of supported product codes (e.g. ["METAR"], ["METAR","TAF"]) — used to
     * skip TAF lookups for airports that never publish one.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class StationInfo {
        @JsonProperty("icaoId") public String icaoId;
        @JsonProperty("iataId") public String iataId;
        @JsonProperty("site") public String site;
        @JsonProperty("country") public String country;
        @JsonProperty("lat") public Double lat;
        @JsonProperty("lon") public Double lon;
        @JsonProperty("siteType") public List<String> siteType;
        public double distanceNm;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TafEntry(@JsonProperty("rawTAF") String rawTAF) {}
}
