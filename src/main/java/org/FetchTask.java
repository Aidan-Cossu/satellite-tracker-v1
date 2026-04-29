package org;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.TimerTask;

public class FetchTask extends TimerTask {
    private final HttpClient fetchClient;
    private final HttpRequest fetchRequest;
    private final Database fetchDatabase;
    private final MapViewer fetchMapViewer;

    public FetchTask(HttpClient client, HttpRequest request, Database database, MapViewer mapViewer) {
        this.fetchClient = client;
        this.fetchRequest = request;
        this.fetchDatabase = database;
        this.fetchMapViewer = mapViewer;
    }

    @Override
    public void run() {

        try {
            HttpResponse<String> response = this.fetchClient.send(this.fetchRequest, HttpResponse.BodyHandlers.ofString());

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

                this.fetchDatabase.savePosition(id, satname,  satlatitude, satlongitude, sataltitude);

                this.fetchMapViewer.updatePosition(satlatitude, satlongitude, satname);

//                System.out.println("ID: " + id +" \nname: " + satname + "\nLatitude: " + satlatitude + "\nLongitude: " + satlongitude + "\nAltitude: " + sataltitude + " KM");
//                System.out.println(response.body());
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
