package org;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class FetchTask extends TimerTask {
    private final HttpClient fetchClient;
    private final HttpClient fetchLocationClient;
    private final String  API_KEY;
    private final Database fetchDatabase;
    private final MapViewer fetchMapViewer;
    private final String locationApiKey;
    private final Set<String> selectedOrgs;

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

                ZoneId cestZone = ZoneId.of("Europe/Brussels");

                HttpResponse<String> response = fetchClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject rootObject = JsonParser.parseString(response.body()).getAsJsonObject();

                    JsonElement jsonInfoElement = rootObject.get("info");
                    JsonObject jsonInfoObject = jsonInfoElement.getAsJsonObject();

                    JsonElement jsonPositionsElement = rootObject.get("positions");
                    JsonArray jsonPositionsArray = jsonPositionsElement.getAsJsonArray();

                    int id = jsonInfoObject.get("satid").getAsInt();
                    String satname = jsonInfoObject.get("satname").getAsString();

                    JsonElement positionElement = jsonPositionsArray.get(0);

                    float satlatitude = positionElement.getAsJsonObject().get("satlatitude").getAsFloat();
                    float satlongitude = positionElement.getAsJsonObject().get("satlongitude").getAsFloat();
                    float sataltitude = positionElement.getAsJsonObject().get("sataltitude").getAsFloat();
                    boolean sateclipsed = positionElement.getAsJsonObject().get("eclipsed").getAsBoolean();

                    ZonedDateTime cestNow = ZonedDateTime.now(cestZone);

                    String locationURL = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude="
                            + satlatitude + "&longitude=" + satlongitude + "&localityLanguage=en&key=" + locationApiKey;

                    HttpRequest locationRequest = HttpRequest.newBuilder()
                            .uri(URI.create(locationURL))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();

                    HttpResponse<String> locationResponse = this.fetchLocationClient.send(locationRequest, HttpResponse.BodyHandlers.ofString());
                    JsonObject rootLocationObject = JsonParser.parseString(locationResponse.body()).getAsJsonObject();

                    JsonElement countryElement = rootLocationObject.get("countryname");
                    String satlocation = (countryElement != null && !countryElement.getAsString().isBlank())
                            ? countryElement.getAsString()
                            : "ocean/sea";

                    this.fetchDatabase.savePosition(id, satname,  satlatitude, satlongitude, sataltitude, cestNow);

                    positions.add(new SatellitePosition(satname, satlatitude, satlongitude, sataltitude, sateclipsed, satlocation));

                    //                System.out.println("ID: " + id +" \nname: " + satname + "\nLatitude: " + satlatitude + "\nLongitude: " + satlongitude + "\nAltitude: " + sataltitude + " KM");
                    //                System.out.println(response.body());
                    //                System.out.println(locationResponse.body());
                } else {
                    System.out.println("Request failed with status: " + response.statusCode());
                }
            }

            fetchMapViewer.updatePosition(positions);
        } catch (HttpTimeoutException e) {
            try {
                throw new HttpTimeoutException(e.getMessage());
            } catch (HttpTimeoutException ex) {
                throw new RuntimeException(ex);
            }
        } catch (IOException e) {
            try {
                throw new IOException(e.getMessage());
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        } catch (InterruptedException e) {
            try {
                throw new InterruptedException(e.getMessage());
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}
