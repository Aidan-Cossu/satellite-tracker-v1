package org;

//region Imports
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Properties;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.*;
// endregion

public class Main {
    public static void main(String[] args) throws Exception {
        Database database = new Database();
        database.createTable();
        MapViewer mapViewer = new MapViewer();

        // Delete this till
        String rootPath = Thread.currentThread().getContextClassLoader().getResource("").getPath();
        String appConfigPath = rootPath + "satellite-tracker.properties";

        Properties satAppProps = new Properties();
        satAppProps.load(new FileInputStream(appConfigPath));

        String API_KEY = satAppProps.getProperty("api");
        // This and make a string as following: String API_KEY = "YOUR_API_KEY_HERE" -> "YOUR_API_KEY_HERE" Should be changed for the API key given by N2YO.com
        String url = "https://api.n2yo.com/rest/v1/satellite/positions/25544/41.702/-76.014/0/2/&apiKey=" + API_KEY;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject rootObject = JsonParser.parseString(response.body()).getAsJsonObject();

                JsonElement jsonInfoElement = rootObject.get("info");
                JsonObject jsonInfoObject = jsonInfoElement.getAsJsonObject();

                JsonElement jsonPositionsElement = rootObject.get("positions");
                JsonArray jsonPositionsArray = jsonPositionsElement.getAsJsonArray();

                int id = jsonInfoObject.get("satid").getAsInt();
                String satname = jsonInfoObject.get("satname").getAsString();

                JsonElement positions = jsonPositionsArray.get(0);

                float satlatitude = positions.getAsJsonObject().get("satlatitude").getAsFloat();
                float satlongitude = positions.getAsJsonObject().get("satlongitude").getAsFloat();
                float sataltitude = positions.getAsJsonObject().get("sataltitude").getAsFloat();

                database.savePosition(id, satname,  satlatitude, satlongitude, sataltitude);

                mapViewer.map((double) satlatitude, (double) satlongitude, satname);

//                System.out.println("ID: " + id +" \nname: " + satname + "\nLatitude: " + satlatitude + "\nLongitude: " + satlongitude + "\nAltitude: " + sataltitude + " KM");
//                System.out.println(response.body());
            } else {
                System.out.println("Request failed with status: " + response.statusCode());
            }
        } catch (HttpTimeoutException e) {
            throw new HttpTimeoutException(e.getMessage());
        } catch (IOException e) {
            throw new IOException(e.getMessage());
        } catch (InterruptedException e) {
            throw new InterruptedException(e.getMessage());
        }

    }
}