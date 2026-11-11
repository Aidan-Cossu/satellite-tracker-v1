package org;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.net.URL;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Swing panel that embeds the interactive world map ({@code /map/map.html}, built with amCharts)
 * in a JavaFX {@link WebView}.
 *
 * Communication works in both directions:
 *
 * Java -> JavaScript: {@code webEngine.executeScript(...)} calls functions such as
 * {@code window.updateSatellites(...)}. Data is sent as JSON.
 * JavaScript -> Java: the page calls {@link JavaBridge#onClicked(String)} when a
 * satellite marker is clicked.
 *
 *
 * JavaFX code must run on the JavaFX Application Thread (hence {@code Platform.runLater}),
 * while Swing code runs on the EDT. This class is the boundary between the two worlds.
 * Anything that arrives before the page has finished loading is remembered ("pending") and
 * applied as soon as the page is ready.
 */
// Must be public: JavaFX calls the JavaBridge methods reflectively from JavaScript.
public class MapPanel extends JPanel {

    private final JFXPanel fxPanel = new JFXPanel();
    private final Gson gson = new Gson();
    private final Consumer<String> onSatelliteClicked;

    // The fields below are only accessed on the JavaFX thread.
    private WebEngine webEngine;
    private volatile boolean ready = false;
    private List<SatellitePosition> pendingUpdate = null;
    private List<double[]> pendingTrajectory = null;
    private BooleanSupplier pendingTrajectoryIsCurrent;
    private String pendingSelectedSatellite = null;
    private JavaBridge bridge; // strong reference, otherwise JavaFX may garbage-collect it

    public MapPanel(Consumer<String> onSatelliteClicked) {
        this.onSatelliteClicked = onSatelliteClicked;

        setLayout(new BorderLayout());
        add(fxPanel, BorderLayout.CENTER);

        // Keep the JavaFX runtime alive even if the WebView is temporarily not shown.
        Platform.setImplicitExit(false);

        Platform.runLater(() -> {
            WebView webView = new WebView();
            webEngine = webView.getEngine();
            fxPanel.setScene(new Scene(webView));

            URL url = getClass().getResource("/map/map.html");

            if (url == null) {
                System.err.println("map.html not found");
                return;
            }

            // Register the listener before load() so that no state change can be missed.
            webEngine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == Worker.State.SUCCEEDED) {
                    onPageLoaded();
                } else if (state == Worker.State.FAILED) {
                    System.err.println("Failed to load map: " + webEngine.getLoadWorker().getException());
                }
            });

            webEngine.load(url.toExternalForm());
        });
    }

    /** The page is loaded: connect the Java bridge, then flush everything queued so far. */
    private void onPageLoaded() {
        bridge = new JavaBridge();

        // Expose the bridge to JavaScript as window.javaBridge.
        JSObject window = (JSObject) webEngine.executeScript("window");
        window.setMember("javaBridge", bridge);

        // Forward console.log from the page to the Java console (the bridge must exist first).
        webEngine.executeScript(
                "console.log = function() {" +
                        "    var args = Array.prototype.slice.call(arguments);" +
                        "    window.javaBridge.log(args.join(' '));" +
                        "};");

        // Called by map.html when a satellite marker is clicked.
        webEngine.executeScript(
                "window.onSatelliteClicked = function(name) {" +
                        "    window.javaBridge.onClicked(name);" +
                        "};");

        ready = true;

        if (pendingSelectedSatellite != null) {
            pushSelectedSatellite(pendingSelectedSatellite);
            pendingSelectedSatellite = null;
        }

        if (pendingUpdate != null) {
            pushToJS(pendingUpdate);
            pendingUpdate = null;
        }

        if (pendingTrajectory != null) {
            if (pendingTrajectoryIsCurrent == null || pendingTrajectoryIsCurrent.getAsBoolean()) {
                pushTrajectory(pendingTrajectory);
            }

            pendingTrajectory = null;
            pendingTrajectoryIsCurrent = null;
        }
    }

    /** Highlights the given satellite on the map. */
    public void setSelectedSatellite(String name) {
        if (name == null) {
            return;
        }

        Platform.runLater(() -> {
            if (!ready || webEngine == null) {
                pendingSelectedSatellite = name;
                return;
            }

            pushSelectedSatellite(name);
        });
    }

    public void setTrajectory(List<double[]> trajectory) {
        setTrajectory(trajectory, () -> true);
    }

    /**
     * Draws a predicted ground track ({lat, lon} pairs). {@code isCurrentRequest} is checked right
     * before drawing so that a slow, outdated request cannot overwrite a newer one.
     */
    public void setTrajectory(List<double[]> trajectory, BooleanSupplier isCurrentRequest) {
        if (trajectory == null) {
            return;
        }

        Platform.runLater(() -> {
            if (!isCurrentRequest.getAsBoolean()) {
                return;
            }

            if (!ready || webEngine == null) {
                pendingTrajectory = trajectory;
                pendingTrajectoryIsCurrent = isCurrentRequest;
                return;
            }

            pushTrajectory(trajectory);
        });
    }

    /** Moves the satellite markers to their new positions. */
    public void updatePosition(List<SatellitePosition> satPosition) {
        if (satPosition == null) {
            return;
        }

        Platform.runLater(() -> {
            if (!ready || webEngine == null) {
                pendingUpdate = satPosition; // only the newest update matters
                return;
            }

            pushToJS(satPosition);
        });
    }

    /**
     * Turns a value into a JavaScript expression that is safe to embed in a script string.
     * The JSON is serialised twice: once to JSON, and once more to quote it as a JS string
     * literal, which {@code JSON.parse} then reads back. This avoids any injection or escaping
     * problems with quotes in names.
     */
    private String jsonLiteral(Object value) {
        return "JSON.parse(" + gson.toJson(gson.toJson(value)) + ")";
    }

    private void pushSelectedSatellite(String name) {
        webEngine.executeScript("window.setSelectedSatellite(" + jsonLiteral(name) + ");");
    }

    private void pushTrajectory(List<double[]> trajectory) {
        try {
            JsonArray points = new JsonArray();

            for (double[] point : trajectory) {
                if (point == null || point.length < 2) {
                    continue;
                }

                JsonObject item = new JsonObject();
                item.addProperty("lat", point[0]);
                item.addProperty("lon", point[1]);
                points.add(item);
            }

            webEngine.executeScript("window.setTrajectory(" + jsonLiteral(points) + ");");

        } catch (Exception e) {
            System.err.println("Trajectory JS error: " + e.getMessage());
        }
    }

    private void pushToJS(List<SatellitePosition> satPosition) {
        try {
            JsonArray satellites = new JsonArray();

            for (SatellitePosition satellite : satPosition) {
                JsonObject item = new JsonObject();
                item.addProperty("lat", satellite.lat());
                item.addProperty("lon", satellite.lon());
                item.addProperty("name", satellite.name());
                satellites.add(item);
            }

            webEngine.executeScript("window.updateSatellites(" + jsonLiteral(satellites) + ");");

        } catch (Exception e) {
            System.err.println("JS update error: " + e.getMessage());
        }
    }

    /** Methods that JavaScript is allowed to call (exposed as {@code window.javaBridge}). */
    public class JavaBridge {
        /** A marker was clicked on the map; hand the name over to the Swing thread. */
        public void onClicked(String name) {
            if (name == null || name.isBlank() || onSatelliteClicked == null) {
                return;
            }

            SwingUtilities.invokeLater(() -> onSatelliteClicked.accept(name));
        }

        /** Receives console.log output from the page. */
        public void log(String msg) {
            System.out.println("JS: " + msg);
        }

        public void onMapReady() {
            Platform.runLater(() -> {
                ready = true;
                onPageLoaded();
            });
        }
    }
}