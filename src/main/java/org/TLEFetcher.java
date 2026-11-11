
package org;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Downloads TLEs (two-line element sets describing a satellite's orbit) from the N2YO API.
 *
 * TLEs stay accurate for days, so each one is cached for 24 hours. That keeps the number of
 * API requests low and makes repeated clicks on the same satellite instant.
 */
public class TLEFetcher {
    private static final Duration CACHE_DURATION = Duration.ofHours(24);

    private final HttpClient fetchClient;
    private final String API_KEY;

    // Thread-safe: used from the background trajectory thread.
    private final ConcurrentMap<Integer, CachedTLE> cache = new ConcurrentHashMap<>();

    public TLEFetcher(HttpClient fetchClient, String API_KEY) {
        this.fetchClient = fetchClient;
        this.API_KEY = API_KEY;
    }

    /**
     * Returns the TLE for the given NORAD catalogue id (cached if recent enough).
     *
     * @throws IOException if the API request fails or the response is not a valid TLE
     */
    public TLEData fetch(int noradId) throws IOException, InterruptedException {
        CachedTLE cached = cache.get(noradId);

        if (cached != null && Instant.now().isBefore(cached.fetchedAt().plus(CACHE_DURATION))) {
            return cached.tleData();
        }

        String url = "https://api.n2yo.com/rest/v1/satellite/tle/" + noradId + "/&apiKey=" + API_KEY;

        // A timeout so a stalled connection cannot block the worker thread forever.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = fetchClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("TLE API returned HTTP " + response.statusCode());
        }

        JsonObject rootObject;

        try {
            rootObject = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid JSON returned by TLE API", e);
        }

        // N2YO sends errors as normal JSON without a "tle" field, so validate before using it.
        if (!rootObject.has("tle") || rootObject.get("tle").isJsonNull() || !rootObject.get("tle").isJsonPrimitive()) {
            throw new IOException("TLE API response does not contain a valid 'tle' field");
        }

        // The field holds both lines separated by a line break; line 1 starts with "1 ", line 2 with "2 ".
        String tle = rootObject.get("tle").getAsString().trim();
        String[] lines = tle.split("\\r?\\n");

        if (lines.length != 2 || !lines[0].trim().startsWith("1 ") || !lines[1].trim().startsWith("2 ")) {
            throw new IOException("Invalid TLE returned by API");
        }

        TLEData tleData = new TLEData(lines[0].trim(), lines[1].trim()
        );

        cache.put(noradId, new CachedTLE(tleData, Instant.now()));

        return tleData;
    }

    private record CachedTLE(TLEData tleData, Instant fetchedAt) {}
}
