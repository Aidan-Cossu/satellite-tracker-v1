package org;

import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
/**
 * Entry point of the Satellite Tracker.
 *
 * Start-up order:
 *
 * Load the Orekit data (needed for orbit propagation).
 * Read API keys from environment variables or a properties file.
 * Create the SQLite database and wire up the components.
 * Open the Swing window and start the background schedulers.
 *
 * API keys are never hard-coded: see {@code satellite-tracker.properties.example}.
 */
public class Main {
    private static final String CONFIG_FILE = "satellite-tracker.properties";

    public static void main(String[] args) {
        try {
            run();
        } catch (Exception e) {
            System.err.println("CRITICAL: Application failed to start.");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        loadOrekitData();

        Properties config = loadConfig();

        // Environment variables take priority over the properties file.
        String apiKey = firstNonBlank(System.getenv("N2YO_API_KEY"), config.getProperty("api"));
        String locationApiKey = firstNonBlank(System.getenv("BIGDATACLOUD_API_KEY"), config.getProperty("locationapi"));

        if (apiKey == null) {
            throw new IllegalStateException("No N2YO API key. Set N2YO_API_KEY or add 'api=...' to " + CONFIG_FILE);
        }

        Database database = new Database();
        try {
            database.createTable();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialise database: " + e.getMessage(), e);
        }

        // Organisations currently selected in the filter panel (empty = show everything).
        // A concurrent set because the Swing thread writes it while the scheduler reads it.
        Set<String> selectedOrgs = ConcurrentHashMap.newKeySet();

        // Separate clients so the two APIs can have independent settings later on.
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpClient locationClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        TLEFetcher tleFetcher = new TLEFetcher(client, apiKey);
        OrbitPredictor orbitPredictor = new OrbitPredictor();
        MapViewer mapViewer = new MapViewer(tleFetcher, orbitPredictor);
        FetchTask fetchTask = new FetchTask(client, apiKey, database, mapViewer, locationClient, locationApiKey, selectedOrgs);

        // All Swing components must be created on the Event Dispatch Thread.
        SwingUtilities.invokeLater(() -> mapViewer.show(selectedOrgs, fetchTask));

        // Daemon threads so the JVM can exit when the window is closed.
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "scheduler");
            t.setDaemon(true);
            return t;
        });

        // Update positions once per second. FetchTask only calls the API every few minutes
        // (see its class comment), so this stays well within the free rate limit.
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                fetchTask.fetchNow();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }, 0, 1, TimeUnit.SECONDS);

        // Retention: delete rows older than the retention period every few hours.
        scheduler.scheduleWithFixedDelay(() -> {
                    try {
                        fetchTask.fetchNow();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                },
                0, 1, TimeUnit.SECONDS
        );
    }

    /**
     * Registers the Orekit data folder (leap seconds, Earth orientation, ...).
     * Looks for ./orekit-data first (works when packaged as a JAR), then the classpath
     * (works when running from the IDE).
     */
    private static void loadOrekitData() throws Exception {
        Path dir = Path.of("orekit-data");

        if (!Files.isDirectory(dir)) {
            URL resource = Main.class.getResource("/orekit-data");

            if (resource != null && "file".equals(resource.getProtocol())) {
                dir = Path.of(resource.toURI());
            } else {
                throw new IllegalStateException("orekit-data folder not found. Put it next to the application (./orekit-data).");
            }
        }

        DataContext.getDefault().getDataProvidersManager()
                .addProvider(new DirectoryCrawler(dir.toFile()));
    }

    /** Loads the properties file from the working directory, falling back to the classpath. */
    private static Properties loadConfig() throws IOException {
        Properties props = new Properties();
        Path external = Path.of(CONFIG_FILE);

        if (Files.exists(external)) {
            try (InputStream in = Files.newInputStream(external)) {
                props.load(in);
            }
        } else {
            try (InputStream in = Main.class.getResourceAsStream("/" + CONFIG_FILE)) {
                if (in != null) {
                    props.load(in);
                }
            }
        }

        return props;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}