package org;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;


public class TLEFetcher {
    private final HttpClient fetchClient;
    private final String API_KEY;

    public TLEFetcher(HttpClient fetchClient, String API_KEY) {
        this.fetchClient = fetchClient;
        this.API_KEY = API_KEY;
    }

    public TLEData fetch(int noradId) throws IOException, InterruptedException {
        String url = "https://api.n2yo.com/rest/v1/satellite/tle/" + noradId + "/&apiKey=" + API_KEY;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = fetchClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonObject rootObject = JsonParser.parseString(response.body()).getAsJsonObject();

        String tle = rootObject.get("tle").getAsString();

        String[] lines = tle.split("\r?\n");

        if (lines.length != 2) {
            throw new IOException("Invalid TLE returned by API");
        }

        return new TLEData(lines[0], lines[1]);
    }
}
