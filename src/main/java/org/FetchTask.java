package org;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.TimerTask;

public class FetchTask extends TimerTask {
    private final HttpClient fetchClient;
    private final HttpRequest fetchRequest;
    private final HttpClient fetchLocationClient;
    private final Database fetchDatabase;
    private final MapViewer fetchMapViewer;
    private final String locationApiKey;

    public FetchTask(HttpClient client, HttpRequest request, Database database, MapViewer mapViewer, HttpClient locationClient, String locationApiKey) {
        this.fetchClient = client;
        this.fetchRequest = request;
        this.fetchLocationClient = locationClient;
        this.fetchDatabase = database;
        this.fetchMapViewer = mapViewer;
        this.locationApiKey = locationApiKey;
    }

    @Override
    public void run() {

        try {
            HttpResponse<String> response = this.fetchClient.send(this.fetchRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                ZoneId cestZone = ZoneId.of("Europe/Brussels");
                ZonedDateTime cestNow = ZonedDateTime.now(cestZone);
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");

                JsonObject rootObject = JsonParser.parseString(response.body()).getAsJsonObject();

                JsonElement jsonInfoElement = rootObject.get("info");
                JsonObject jsonInfoObject = jsonInfoElement.getAsJsonObject();

                JsonElement jsonPositionsElement = rootObject.get("positions");
                JsonArray jsonPositionsArray = jsonPositionsElement.getAsJsonArray();

                int id = jsonInfoObject.get("satid").getAsInt();
                String satname = jsonInfoObject.get("satname").getAsString();

                JsonElement positions = jsonPositionsArray.get(0);
                JsonElement locationPositions = jsonPositionsArray.get(0);

                float satlatitude = positions.getAsJsonObject().get("satlatitude").getAsFloat();
                float satlongitude = positions.getAsJsonObject().get("satlongitude").getAsFloat();
                float sataltitude = positions.getAsJsonObject().get("sataltitude").getAsFloat();
                boolean sateclipsed = positions.getAsJsonObject().get("eclipsed").getAsBoolean();

                String locationURL = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude="
                        + satlatitude + "&longitude=" + satlongitude + "&localityLanguage=en&key=" + locationApiKey;

                HttpRequest locationRequest = HttpRequest.newBuilder()
                        .uri(URI.create(locationURL))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> locationResponse = this.fetchLocationClient.send(locationRequest, HttpResponse.BodyHandlers.ofString());
                JsonObject rootLocationObject = JsonParser.parseString(locationResponse.body()).getAsJsonObject();

                String satlocation = rootLocationObject.getAsJsonObject().get("countryName").getAsString();

                this.fetchDatabase.savePosition(id, satname,  satlatitude, satlongitude, sataltitude, cestNow);
                this.fetchMapViewer.updatePosition(satlatitude, satlongitude, sataltitude, satname, sateclipsed, satlocation);

                String rootPath = Thread.currentThread().getContextClassLoader().getResource("").getPath();

                Properties satAppProps = new Properties();

//                System.out.println("ID: " + id +" \nname: " + satname + "\nLatitude: " + satlatitude + "\nLongitude: " + satlongitude + "\nAltitude: " + sataltitude + " KM");
//                System.out.println(response.body());
//                System.out.println(locationResponse.body());
            } else {
                System.out.println("Request failed with status: " + response.statusCode());
            }
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
