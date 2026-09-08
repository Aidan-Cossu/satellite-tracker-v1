package org;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class FetchTask extends TimerTask {
    private final HttpClient fetchClient;
    private final HttpClient fetchLocationClient;
    private final String  API_KEY;
    private final Database fetchDatabase;
    private final MapViewer fetchMapViewer;
    private final String locationApiKey;
    private final Set<String> selectedOrgs;
    private final Map<Integer, SatellitePosition> lastPositions = new HashMap<>();
    private final Map<Integer, Long> lastTimes = new HashMap<>();
    private final Map<Integer, Double> velocitySmooth = new HashMap<>();
    private static final double ALPHA = 0.15;
    private static final ZoneId CEST_ZONE = ZoneId.of("Europe/Brussels");

    public FetchTask(HttpClient client, String API_KEY, Database database, MapViewer mapViewer, HttpClient locationClient, String locationApiKey, Set<String> selectedOrgs) {
        this.fetchClient = client;
        this.fetchLocationClient = locationClient;
        this.API_KEY = API_KEY;
        this.fetchDatabase = database;
        this.fetchMapViewer = mapViewer;
        this.locationApiKey = locationApiKey;
        this.selectedOrgs = selectedOrgs;
    }

    @Override
    public void run() {
        fetchNow();
    }

    public void fetchNow() {
        List<SatelliteRecord> filtered = SatelliteList.satellites().stream()
                .filter(sat -> selectedOrgs.isEmpty() || selectedOrgs.contains(sat.organisation()))
                .collect(Collectors.toList());

        try {
            List<SatellitePosition> positions = new ArrayList<>();

            for (SatelliteRecord satellite : filtered) {
                String url = "https://api.n2yo.com/rest/v1/satellite/positions/" + satellite.noradId() + "/41.702/-76.014/0/2/&apiKey=" + API_KEY;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> response = fetchClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject rootObject = JsonParser.parseString(response.body()).getAsJsonObject();

                    if (rootObject == null
                            || !rootObject.has("info")
                            || !rootObject.has("positions")) {
                        System.out.println("Invalid API response: " + response.body());
                        continue;
                    }

                    JsonObject jsonInfoObject = rootObject.getAsJsonObject("info");
                    JsonArray jsonPositionsArray = rootObject.getAsJsonArray("positions");

                    int id = jsonInfoObject.get("satid").getAsInt();
                    String satname = jsonInfoObject.get("satname").getAsString();

                    JsonElement positionElement = jsonPositionsArray.get(0);
                    JsonObject positionJson = positionElement.getAsJsonObject();

                    float satlatitude = positionJson.get("satlatitude").getAsFloat();
                    float satlongitude = positionJson.get("satlongitude").getAsFloat();
                    float sataltitude = positionJson.get("sataltitude").getAsFloat();
                    boolean sateclipsed = positionJson.get("eclipsed").getAsBoolean();

                    ZonedDateTime cestNow = ZonedDateTime.now(CEST_ZONE);

                    String locationURL = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude="
                            + satlatitude + "&longitude=" + satlongitude + "&localityLanguage=en&key=" + locationApiKey;

                    HttpRequest locationRequest = HttpRequest.newBuilder()
                            .uri(URI.create(locationURL))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();

                    HttpResponse<String> locationResponse = this.fetchLocationClient.send(locationRequest, HttpResponse.BodyHandlers.ofString());
                    JsonObject rootLocationObject = JsonParser.parseString(locationResponse.body()).getAsJsonObject();

                    String satlocation = "ocean/sea";

                    if (rootLocationObject != null) {

                        if (rootLocationObject.has("countryName")
                                && !rootLocationObject.get("countryName").isJsonNull()
                                && !rootLocationObject.get("countryName").getAsString().isBlank()) {

                            satlocation = rootLocationObject.get("countryName").getAsString();

                        } else if (rootLocationObject.has("principalSubdivision")
                                && !rootLocationObject.get("principalSubdivision").isJsonNull()) {

                            satlocation = rootLocationObject.get("principalSubdivision").getAsString();

                        } else if (rootLocationObject.has("locality")
                                && !rootLocationObject.get("locality").isJsonNull()) {

                            satlocation = rootLocationObject.get("locality").getAsString();
                        }
                    }

                    long now = System.currentTimeMillis();

                    SatellitePosition last = lastPositions.get(id);
                    Long lastTime = lastTimes.get(id);

                    double velocity = 0;

                    if (last != null && lastTime != null) {
                        double distance = fetchMapViewer.distance3D(
                                last.lat(), last.lon(), last.alt(),
                                satlatitude, satlongitude, sataltitude
                        );

                        double seconds = (now - lastTime) / 1000.0;

                        if (seconds > 0) {
                            velocity = (distance / seconds) * 3600.0;
                        }
                    }

                    SatellitePosition satellitePosition = new SatellitePosition(
                            satname, satlatitude, satlongitude, sataltitude, sateclipsed, satlocation
                    );

                    positions.add(satellitePosition);
                    lastPositions.put(id, satellitePosition);

                    lastTimes.put(id, now);

                    double previous = velocitySmooth.getOrDefault(id, velocity);
                    double smoothed = ALPHA * velocity + (1 - ALPHA) * previous;

                    velocitySmooth.put(id, smoothed);

                    this.fetchDatabase.savePosition(id, satname, satlatitude, satlongitude, sataltitude, cestNow, smoothed, String.valueOf(sateclipsed ? "eclipsed" : "daylight"), satlocation);

                } else {
                    System.out.println("Request failed with status: " + response.statusCode());
                }
            }

            fetchMapViewer.updatePosition(positions);

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
