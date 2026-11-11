package org;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Produces the live satellite data shown on the map.
 *
 * Why buffering? The N2YO free tier allows about 1,000 requests per hour. Asking for
 * a fresh position every second for every satellite would exceed that within minutes. Instead,
 * N2YO's positions endpoint is asked for 300 consecutive one-second positions at once. Those are
 * kept in memory and played back one per second, and a new batch is only downloaded when the
 * buffer is almost empty (roughly every 4-5 minutes per satellite).
 *
 * On every call to {@link #fetchNow()} this class:
 *
 * picks the buffered sample matching the current time,
 * computes the satellite's speed,
 * looks up a human-readable location (asynchronously, and rarely),
 * stores a row in the database (throttled), and
 * hands the result to the UI.
 *
 */
public class FetchTask {

    private static final String POSITIONS_URL = "https://api.n2yo.com/rest/v1/satellite/positions/";

    // Observer location (lat/lng/alt). N2YO requires it, but it only affects azimuth/elevation,
    // which this app does not use, so any valid value works.
    private static final String OBSERVER = "41.702/-76.014/0";

    private static final int SAMPLES_PER_REQUEST = 300;         // N2YO maximum per call
    private static final long REFILL_MARGIN_S = 30;             // refill when < 30 s of data is left
    private static final long RETRY_BACKOFF_MS = 15_000;        // wait after a failed download
    private static final long LOCATION_REFRESH_MS = 300_000;    // reverse-geocode at most every 5 min
    private static final long SAVE_INTERVAL_S = 10;             // store one DB row per satellite per 10 s

    // Earth's sidereal rotation rate in degrees per second (one turn per 86164.0905 s).
    private static final double EARTH_ROTATION_DEG_PER_S = 360.0 / 86164.0905;

    /** One position reported by N2YO for a given second (Earth-fixed lat/lon, altitude in km). */
    private record Sample(long timestamp, double lat, double lon, double alt, boolean eclipsed) {}

    private final HttpClient fetchClient;
    private final HttpClient fetchLocationClient;
    private final String apiKey;
    private final Database fetchDatabase;
    private final MapViewer fetchMapViewer;
    private final String locationApiKey;
    private final Set<String> selectedOrgs;

    // The three maps below are only used inside the synchronized fetchNow(), so plain HashMaps are fine.
    private final Map<Integer, List<Sample>> buffers = new HashMap<>();     // NORAD id -> buffered positions
    private final Map<Integer, Long> retryAfterMs = new HashMap<>();        // NORAD id -> earliest next download
    private final Map<Integer, Long> lastSavedSec = new HashMap<>();        // NORAD id -> last DB write

    // These are written by asynchronous HTTP callbacks, so they must be thread-safe.
    private final Map<Integer, String> locations = new ConcurrentHashMap<>();
    private final Map<Integer, Long> locationRequestedAtMs = new ConcurrentHashMap<>();

    public FetchTask(HttpClient client, String apiKey, Database database, MapViewer mapViewer,
                     HttpClient locationClient, String locationApiKey, Set<String> selectedOrgs) {
        this.fetchClient = client;
        this.fetchLocationClient = locationClient;
        this.apiKey = apiKey;
        this.fetchDatabase = database;
        this.fetchMapViewer = mapViewer;
        this.locationApiKey = locationApiKey;
        this.selectedOrgs = selectedOrgs;
    }

    /**
     * Refreshes all visible satellites. Synchronized because it is called both by the scheduler
     * and by the UI (when the organisation filter changes); two runs must not overlap.
     */
    public synchronized void fetchNow() {
        // Snapshot the filter so it cannot change halfway through the loop.
        Set<String> selectedSnapshot = Set.copyOf(selectedOrgs);

        long nowMs = System.currentTimeMillis();
        long nowSec = nowMs / 1000;

        List<SatellitePosition> positions = new ArrayList<>();
        Map<String, Double> velocities = new HashMap<>();
        List<Database.PositionData> toSave = new ArrayList<>();

        for (SatelliteRecord satellite : SatelliteList.satellites()) {
            // An empty selection means "show all".
            if (!selectedSnapshot.isEmpty() && !selectedSnapshot.contains(satellite.organisation())) {
                continue;
            }

            // One failing satellite must not prevent the others from updating.
            try {
                List<Sample> buffer = bufferFor(satellite, nowSec, nowMs);

                if (buffer == null) {
                    continue;
                }

                // Find the latest sample that is not in the future (the buffer is sorted by time).
                int idx = 0;
                while (idx < buffer.size() - 2 && buffer.get(idx + 1).timestamp() <= nowSec) {
                    idx++;
                }

                Sample current = buffer.get(idx);
                Sample next = buffer.get(idx + 1);

                double velocity = inertialSpeedKmh(current, next);
                String location = locationFor(satellite.noradId(), current.lat(), current.lon(), nowMs);

                positions.add(new SatellitePosition(satellite.noradId(), satellite.name(), current.lat(), current.lon(), current.alt(), current.eclipsed(), location));

                velocities.put(satellite.name(), velocity);

                // Persist only every SAVE_INTERVAL_S seconds; the charts use 15-minute averages anyway.
                long lastSaved = lastSavedSec.getOrDefault(satellite.noradId(), 0L);

                if (nowSec - lastSaved >= SAVE_INTERVAL_S) {
                    lastSavedSec.put(satellite.noradId(), nowSec);

                    toSave.add(new Database.PositionData(
                            satellite.noradId(), satellite.name(),
                            current.lat(), current.lon(), current.alt(),
                            ZonedDateTime.ofInstant(Instant.ofEpochSecond(current.timestamp()), ZoneOffset.UTC),
                            velocity,
                            current.eclipsed() ? "eclipsed" : "daylight",
                            location));
                }

            } catch (Exception e) {
                System.err.println("Failed for " + satellite.name() + ": " + e);
            }
        }

        // One batched transaction for all satellites instead of one per row.
        if (!toSave.isEmpty()) {
            try {
                fetchDatabase.savePositions(toSave);
            } catch (SQLException e) {
                System.err.println("Failed to save satellite positions: " + e.getMessage());
            }
        }

        fetchMapViewer.updatePosition(positions, velocities);
    }

    /**
     * Returns a usable buffer for the satellite, downloading a new one when the current one is
     * missing or nearly used up. Returns null if no valid data is available.
     */
    private List<Sample> bufferFor(SatelliteRecord satellite, long nowSec, long nowMs) {
        int id = satellite.noradId();
        List<Sample> buffer = buffers.get(id);

        boolean needsRefill = buffer == null || buffer.size() < 2 || buffer.get(buffer.size() - 1).timestamp() - nowSec < REFILL_MARGIN_S;

        // After a failure, wait RETRY_BACKOFF_MS so a broken API or rate limit is not hammered.
        if (needsRefill && nowMs >= retryAfterMs.getOrDefault(id, 0L)) {
            try {
                buffer = downloadSamples(satellite);
                buffers.put(id, buffer);
                retryAfterMs.remove(id);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                System.err.println("Position download failed for " + satellite.name() + ": " + e.getMessage());
                retryAfterMs.put(id, nowMs + RETRY_BACKOFF_MS);
            }
        }

        // The old buffer is kept after a failed refill and used until it runs out.
        if (buffer == null || buffer.size() < 2 || buffer.get(buffer.size() - 1).timestamp() < nowSec) {
            return null;
        }

        return buffer;
    }

    /** Downloads {@link #SAMPLES_PER_REQUEST} consecutive one-second positions from N2YO. */
    private List<Sample> downloadSamples(SatelliteRecord satellite) throws IOException, InterruptedException {
        String url = POSITIONS_URL + satellite.noradId() + "/" + OBSERVER + "/" + SAMPLES_PER_REQUEST + "?apiKey=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = fetchClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid JSON from positions API", e);
        }

        // N2YO reports problems (e.g. rate limit exceeded) as HTTP 200 with an "error" field,
        // so the shape of the JSON has to be checked as well.
        if (!root.has("positions") || !root.get("positions").isJsonArray()) {
            throw new IOException("Unexpected response: " + abbreviate(response.body()));
        }

        JsonArray array = root.getAsJsonArray("positions");
        List<Sample> samples = new ArrayList<>(array.size());

        for (JsonElement element : array) {
            JsonObject p = element.getAsJsonObject();

            samples.add(new Sample(p.get("timestamp").getAsLong(), p.get("satlatitude").getAsDouble(), p.get("satlongitude").getAsDouble(), p.get("sataltitude").getAsDouble(), p.get("eclipsed").getAsBoolean()));
        }

        if (samples.size() < 2) {
            throw new IOException("Fewer than 2 positions returned");
        }

        samples.sort(Comparator.comparingLong(Sample::timestamp));

        return List.copyOf(samples);
    }

    /**
     * Speed in km/h relative to inertial space (the "real" orbital speed).
     *
     * N2YO reports Earth-fixed longitude, which turns with the planet. Measuring the distance
     * between two such points would show about 0 km/h for a geostationary satellite, which is
     * wrong: it really moves at roughly 11,000 km/h. Adding the Earth's rotation back to the
     * second longitude corrects for this.
     */
    private double inertialSpeedKmh(Sample a, Sample b) {
        double seconds = b.timestamp() - a.timestamp();

        if (seconds <= 0) {
            return 0;
        }

        double lonA = a.lon();
        double lonB = b.lon() + EARTH_ROTATION_DEG_PER_S * seconds;

        double distanceKm = MapViewer.distance3D(a.lat(), lonA, a.alt(), b.lat(), lonB, b.alt());

        return distanceKm / seconds * 3600.0;
    }

    /**
     * Returns the last known location name immediately and, if it is stale, triggers a refresh
     * in the background. This keeps the one-second update loop from waiting on the network.
     */
    private String locationFor(int id, double lat, double lon, long nowMs) {
        Long last = locationRequestedAtMs.get(id);

        if (last == null || nowMs - last >= LOCATION_REFRESH_MS) {
            locationRequestedAtMs.put(id, nowMs);
            requestLocation(id, lat, lon);
        }

        return locations.getOrDefault(id, "Unknown");
    }

    /** Asynchronous reverse-geocoding request (coordinates -> city/country name). */
    private void requestLocation(int id, double lat, double lon) {
        try {
            StringBuilder url = new StringBuilder("https://api.bigdatacloud.net/data/reverse-geocode-client")
                    .append("?latitude=").append(String.format(Locale.ROOT, "%.5f", lat))
                    .append("&longitude=").append(String.format(Locale.ROOT, "%.5f", lon))
                    .append("&localityLanguage=en");

            if (locationApiKey != null && !locationApiKey.isBlank()) {
                url.append("&key=").append(URLEncoder.encode(locationApiKey, StandardCharsets.UTF_8));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            fetchLocationClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() != 200) {
                            System.err.println("Geocoding API returned HTTP " + response.statusCode());
                            return;
                        }
                        locations.put(id, parseLocation(response.body()));
                    })
                    .exceptionally(ex -> {
                        System.err.println("Geocoding failed: " + ex.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            System.err.println("Geocoding request failed: " + e.getMessage());
        }
    }

    /** Picks the most specific non-empty name; falls back to "ocean/sea" when nothing is returned. */
    private static String parseLocation(String body) {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        for (String key : new String[]{"city", "locality", "principalSubdivision", "countryName"}) {
            String value = text(json, key);

            if (value != null) {
                return value;
            }
        }

        return "ocean/sea";
    }


    /** Null-safe string read: missing, JSON null and blank values all become null. */
    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);

        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }

        String value = element.getAsString().trim();

        return value.isEmpty() ? null : value;
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}