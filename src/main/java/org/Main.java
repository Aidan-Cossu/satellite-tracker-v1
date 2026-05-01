package org;

//region Imports
import javax.imageio.IIOException;
import javax.swing.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;
// endregion

public class Main {
    public static void main(String[] args) throws Exception {
        try {
            Set<String> selectedOrgs = ConcurrentHashMap.newKeySet();

            Database database = new Database();
            database.createTable();
            MapViewer mapViewer = new MapViewer();
            Timer timer = new Timer();

            // Delete this till
            String rootPath = Thread.currentThread().getContextClassLoader().getResource("").getPath();
            String appConfigPath = rootPath + "satellite-tracker.properties";

            Properties satAppProps = new Properties();
            satAppProps.load(new FileInputStream(appConfigPath));

            String API_KEY = satAppProps.getProperty("api");
            // This and make a string as following: String API_KEY = "YOUR_API_KEY_HERE" -> "YOUR_API_KEY_HERE" Should be changed for the API key given by N2YO.com
            String LOCATION_API_KEY = satAppProps.getProperty("locationapi");

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpClient locationClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            FetchTask fetchTask = new FetchTask(client, API_KEY, database, mapViewer, locationClient, LOCATION_API_KEY, selectedOrgs);

            SwingUtilities.invokeLater(() -> {
                try {
                    mapViewer.show(0,0, 0,"name", false, "unknown", selectedOrgs, fetchTask);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            timer.scheduleAtFixedRate(fetchTask, 0, 10000);
        } catch (Exception e) {
            System.err.println("CRITICAL: Application failed to start.");
            e.printStackTrace();
        }
    }
}