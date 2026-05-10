package dev.example.flighttracker.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates the FAA's coded METAR/TAF strings into plain English.
 *
 * The codes are dense but very regular — each whitespace-separated token
 * matches one of a small set of patterns (wind, visibility, clouds,
 * weather phenomena, temperature, altimeter, change-group markers, etc.).
 * We classify each token via regex and emit a short human description.
 * Unrecognised tokens are dropped so a malformed or non-standard fragment
 * never breaks the whole report.
 *
 * Reference: FMH-1 (Federal Meteorological Handbook No. 1) Chapter 12.
 */
final class WeatherDecoder {

    private WeatherDecoder() {}

    // -- Token patterns ----------------------------------------------------
    // Wind: dddff[Ggg]KT, with VRB allowed for direction. Example: 25009KT, VRB03KT, 27015G25KT.
    private static final Pattern WIND = Pattern.compile("(\\d{3}|VRB)(\\d{2,3})(?:G(\\d{2,3}))?KT");
    // Visibility in statute miles: 10SM, 1/2SM, P6SM ("plus 6", i.e. >6).
    private static final Pattern VIS_SM = Pattern.compile("(P)?(\\d+(?:/\\d+)?)SM");
    // Cloud layer: cover code + height in hundreds of feet, optional CB/TCU type.
    private static final Pattern CLOUDS = Pattern.compile("(FEW|SCT|BKN|OVC|VV|SKC|CLR|NSC|NCD)(\\d{3})?(CB|TCU)?");
    // Temp/dewpoint, with M prefix for negative. Example: 28/09, M02/M05.
    private static final Pattern TEMP_DEW = Pattern.compile("(M?\\d{2})/(M?\\d{2})");
    // Altimeter: A####=inches Hg×100, Q####=hectopascals.
    private static final Pattern ALT_INHG = Pattern.compile("A(\\d{4})");
    private static final Pattern ALT_HPA = Pattern.compile("Q(\\d{4})");
    // Weather phenomena: optional intensity ± / "VC" (vicinity), then descriptor, then 2-char codes.
    private static final Pattern WX_PHRASE = Pattern.compile(
            "([+-]|VC)?(MI|PR|BC|DR|BL|SH|TS|FZ)?((?:DZ|RA|SN|SG|IC|PL|GR|GS|UP|BR|FG|FU|VA|DU|SA|HZ|PY|PO|SQ|FC|SS|DS){1,3})");
    // TAF "from" group — period boundary. Example: FM102100 = day 10, 21:00 UTC.
    private static final Pattern TAF_FM = Pattern.compile("FM(\\d{2})(\\d{2})(\\d{2})");
    // TAF probability marker. Example: PROB30 (30 % probability of...).
    private static final Pattern TAF_PROB = Pattern.compile("PROB(\\d{2})");
    // TAF temporary/becoming markers — followed by their own period in DDHH/DDHH form.
    private static final Pattern TAF_BECMG_TEMPO = Pattern.compile("(BECMG|TEMPO)");
    private static final Pattern TAF_VALID_PERIOD = Pattern.compile("\\d{4}/\\d{4}");

    // ---------------- METAR ---------------------------------------------

    /**
     * Decodes a full METAR observation. Strips the report-type, station,
     * and timestamp header (we already display those separately) and the
     * trailing RMK section (mostly machine-only data).
     */
    static String decodeMetar(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String body = raw.trim()
                .replaceFirst("^(METAR|SPECI)\\s+", "")
                .replaceFirst("^[A-Z0-9]{4}\\s+\\d{6}Z\\s+", "");
        int rmk = body.indexOf(" RMK ");
        if (rmk > 0) body = body.substring(0, rmk);
        return decodeTokens(body.split("\\s+"));
    }

    // ---------------- TAF -----------------------------------------------

    /**
     * Decodes a TAF forecast. TAFs are a sequence of forecast periods
     * separated by FM (from) markers; we render each period as its own
     * bullet so the timeline reads naturally.
     */
    static String decodeTaf(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // Split on FM markers but keep them as the start of the next chunk.
        String[] parts = raw.trim().split("(?=\\bFM\\d{6})");
        List<String> lines = new ArrayList<>();
        for (String part : parts) {
            Matcher fm = TAF_FM.matcher(part);
            String prefix;
            String[] tokens;
            if (fm.lookingAt()) {
                prefix = String.format("From day %s at %s:%sZ → ",
                        fm.group(1), fm.group(2), fm.group(3));
                tokens = part.substring(fm.end()).trim().split("\\s+");
            } else {
                prefix = "Initial → ";
                tokens = part.trim().split("\\s+");
            }
            String decoded = decodeTokens(tokens);
            if (!decoded.isBlank()) lines.add(prefix + decoded);
        }
        // Two-space indent + dash so each forecast period renders as a
        // nested list item under the parent "**Forecast:**" bullet when
        // the caller pipes the output through marked.js.
        return String.join("\n  - ", prependBullet(lines));
    }

    private static List<String> prependBullet(List<String> lines) {
        if (lines.isEmpty()) return lines;
        // The join above adds "\n  - " *between* entries; we prepend the
        // same prefix to the first line so it's not stuck inline with the
        // parent label.
        List<String> out = new ArrayList<>(lines.size());
        out.add("\n  - " + lines.get(0));
        for (int i = 1; i < lines.size(); i++) out.add(lines.get(i));
        return out;
    }

    // ---------------- Token decoding ------------------------------------

    private static String decodeTokens(String[] tokens) {
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            String d = decodeToken(token);
            if (d == null || d.isBlank()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(d);
        }
        return out.toString();
    }

    private static String decodeToken(String token) {
        if (token.isBlank()) return null;

        // TAF change-group markers — handled specially by the caller for
        // FM, but BECMG / TEMPO / PROB / valid-period groups still appear
        // inline. Render them as natural-language hints.
        Matcher m = TAF_PROB.matcher(token);
        if (m.matches()) return String.format("(%s%% probability)", m.group(1));
        if (TAF_BECMG_TEMPO.matcher(token).matches()) {
            return token.equals("BECMG") ? "(becoming)" : "(temporarily)";
        }
        if (TAF_VALID_PERIOD.matcher(token).matches()) return null; // hide raw period spans
        if (token.matches("\\d{4}/\\d{4}")) return null;

        // Wind: 25009KT, VRB03KT, 27015G25KT. Calm wind is "00000KT".
        m = WIND.matcher(token);
        if (m.matches()) {
            if ("000".equals(m.group(1)) && "00".equals(m.group(2))) return "wind calm";
            String dir = "VRB".equals(m.group(1)) ? "variable" : (m.group(1) + "°");
            String spd = m.group(2);
            String gust = m.group(3);
            String s = "wind " + dir + " at " + spd + " kts";
            if (gust != null) s += String.format(", gusting %s kts", gust);
            return s;
        }

        // Visibility: 10SM, P6SM, 1/2SM.
        m = VIS_SM.matcher(token);
        if (m.matches()) {
            String prefix = "P".equals(m.group(1)) ? ">" : "";
            return "visibility " + prefix + m.group(2) + " SM";
        }

        // Cloud layer.
        m = CLOUDS.matcher(token);
        if (m.matches()) {
            String coverWord = switch (m.group(1)) {
                case "FEW" -> "few clouds";
                case "SCT" -> "scattered clouds";
                case "BKN" -> "broken clouds";
                case "OVC" -> "overcast";
                case "VV"  -> "vertical visibility";
                case "SKC", "CLR" -> "sky clear";
                case "NSC" -> "no significant clouds";
                case "NCD" -> "no clouds detected";
                default    -> m.group(1);
            };
            String alt = m.group(2);
            String type = m.group(3);
            if (alt == null) return coverWord;
            int feet = Integer.parseInt(alt) * 100;
            String s = String.format("%s at %,d ft", coverWord, feet);
            if ("CB".equals(type))  s += " (cumulonimbus)";
            if ("TCU".equals(type)) s += " (towering cumulus)";
            return s;
        }

        // Temperature / dewpoint pair.
        m = TEMP_DEW.matcher(token);
        if (m.matches()) {
            return String.format("temp %d°C, dewpoint %d°C",
                    parseTemp(m.group(1)), parseTemp(m.group(2)));
        }

        // Altimeter (US).
        m = ALT_INHG.matcher(token);
        if (m.matches()) {
            return String.format("altimeter %.2f inHg", Integer.parseInt(m.group(1)) / 100.0);
        }
        // Altimeter (rest of world).
        m = ALT_HPA.matcher(token);
        if (m.matches()) return "altimeter " + m.group(1) + " hPa";

        // Weather phenomena (rain, snow, fog, etc.). Try last because the
        // pattern is permissive and could otherwise capture unrelated tokens.
        m = WX_PHRASE.matcher(token);
        if (m.matches() && m.group(3) != null) {
            return decodeWxPhrase(m.group(1), m.group(2), m.group(3));
        }

        return null; // unrecognised — drop it silently
    }

    private static int parseTemp(String t) {
        return t.startsWith("M") ? -Integer.parseInt(t.substring(1)) : Integer.parseInt(t);
    }

    /** Builds a phrase like "light rain shower" or "heavy thunderstorm with rain and hail". */
    private static String decodeWxPhrase(String intensity, String descriptor, String phenomena) {
        StringBuilder s = new StringBuilder();
        if ("+".equals(intensity)) s.append("heavy ");
        else if ("-".equals(intensity)) s.append("light ");
        else if ("VC".equals(intensity)) s.append("in vicinity: ");

        if (descriptor != null) {
            s.append(switch (descriptor) {
                case "TS" -> "thunderstorm with ";
                case "SH" -> "shower of ";
                case "FZ" -> "freezing ";
                case "BL" -> "blowing ";
                case "DR" -> "drifting ";
                case "MI" -> "shallow ";
                case "BC" -> "patches of ";
                case "PR" -> "partial ";
                default   -> descriptor + " ";
            });
        }

        // Phenomena are a sequence of 2-character codes packed together.
        List<String> words = new ArrayList<>();
        for (int i = 0; i + 2 <= phenomena.length(); i += 2) {
            words.add(switch (phenomena.substring(i, i + 2)) {
                case "DZ" -> "drizzle";
                case "RA" -> "rain";
                case "SN" -> "snow";
                case "SG" -> "snow grains";
                case "IC" -> "ice crystals";
                case "PL" -> "ice pellets";
                case "GR" -> "hail";
                case "GS" -> "small hail";
                case "UP" -> "unknown precipitation";
                case "BR" -> "mist";
                case "FG" -> "fog";
                case "FU" -> "smoke";
                case "VA" -> "volcanic ash";
                case "DU" -> "dust";
                case "SA" -> "sand";
                case "HZ" -> "haze";
                case "PY" -> "spray";
                case "PO" -> "dust whirls";
                case "SQ" -> "squalls";
                case "FC" -> "funnel cloud";
                case "SS" -> "sandstorm";
                case "DS" -> "duststorm";
                default   -> phenomena.substring(i, i + 2);
            });
        }
        s.append(String.join(" and ", words));
        return s.toString().trim();
    }
}
