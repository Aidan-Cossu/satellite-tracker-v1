package org;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.*;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYSplineRenderer;
import org.jfree.data.time.*;
import org.jfree.data.xy.XYDataItem;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public class MapViewer {
    private static final double EARTH_RADIUS_KM = 6371.0088;
    private double previousLat;
    private double previousLon;

    private static final Color BG_DARK = new Color(11, 15, 26);
    private static final Color PANEL = new Color(18, 24, 38);
    private static final Color ACCENT = new Color(63, 185, 255);
    private static final Color TEXT_PRIMARY = new Color(230, 237, 243);
    private static final Color TEXT_SECONDARY = new Color(154, 164, 178);

    private MapPanel mapPane;
    private JLabel latVal;
    private JLabel lonVal;
    private JLabel altVal;
    private JLabel eclipsedVal;
    private JLabel velVal;
    private JLabel locVal;

    private JPanel bottomPanel;

    private long previousTime = 0;
    private final java.util.Deque<SatellitePosition> history = new java.util.ArrayDeque<>();
    private final java.util.Deque<Long> timeHistory = new java.util.ArrayDeque<>();
    private static final int MAX_HISTORY = 12;

    private String selectedSatellite;

    private SidePanel sidePanel;

    private Database.Satellite satellite;
    private final Database db = new Database();

    private final TLEFetcher tleFetcher;
    private final OrbitPredictor orbitPredictor;

    public MapViewer(TLEFetcher tleFetcher, OrbitPredictor orbitPredictor) {
        this.tleFetcher = tleFetcher;
        this.orbitPredictor = orbitPredictor;
    }

    private ChartPanel createChartPanel(String title, String yAxisLabel, XYSeriesCollection dataset, String[] labels, double padding) {
        SymbolAxis xAxis = new SymbolAxis("Time", labels);
        xAxis.setGridBandsVisible(false);
        xAxis.setTickLabelPaint(Color.WHITE);
        xAxis.setLabelPaint(Color.WHITE);

        xAxis.setAutoTickUnitSelection(false);

        xAxis.setVerticalTickLabels(false);

        xAxis.setLowerMargin(0.02);
        xAxis.setUpperMargin(0.02);

        xAxis.setTickLabelPaint(Color.WHITE);
        xAxis.setLabelPaint(Color.WHITE);

        NumberAxis yAxis = new NumberAxis(yAxisLabel);
        yAxis.setAutoRangeIncludesZero(false);
        yAxis.setTickLabelPaint(Color.WHITE);
        yAxis.setLabelPaint(Color.WHITE);

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (int s = 0; s < dataset.getSeriesCount(); s++) {
            XYSeries series = dataset.getSeries(s);

            for (Object obj : series.getItems()) {
                XYDataItem item = (XYDataItem) obj;
                double v = item.getYValue();

                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }

        if (min == Double.MAX_VALUE) min = 0;
        if (max == -Double.MAX_VALUE) max = 1;

        yAxis.setRange(min - padding, max + padding);

        XYSplineRenderer renderer = new XYSplineRenderer();
        renderer.setSeriesPaint(0, Color.CYAN);
        renderer.setDefaultShapesVisible(false);

        XYPlot plot = new XYPlot(dataset, xAxis, yAxis, renderer);

        plot.setBackgroundPaint(BG_DARK);
        plot.setOutlineVisible(false);
        plot.setDomainGridlinePaint(new Color(60, 60, 60));
        plot.setRangeGridlinePaint(new Color(60, 60, 60));

        JFreeChart chart = new JFreeChart(title, JFreeChart.DEFAULT_TITLE_FONT, plot, false);

        chart.setBackgroundPaint(Color.BLACK);
        chart.getTitle().setPaint(Color.WHITE);

        ChartPanel panel = new ChartPanel(chart);
        panel.setPreferredSize(new Dimension(700, 220));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setBackground(Color.BLACK);
        panel.setOpaque(false);

        return panel;
    }

    private long floorTo15Min(long millis) {
        return millis - (millis % (15 * 60 * 1000L));
    }

    public void show(double lat, double lon, double alt, String name, boolean ecl, String loc, Set<String> selectedOrgs, FetchTask fetchTask) throws IOException{
        Image icon = new ImageIcon("ISSIcon.png").getImage();
        JFrame frame = new JFrame("Satellite Tracker");
        frame.pack();
        frame.setMinimumSize(new Dimension(1510, 1000));
        frame.setSize(1200, 1000);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setIconImage(icon);

        previousLat = lat;
        previousLon = lon;
        previousTime = System.currentTimeMillis();

        frame.setLayout(new BorderLayout());

        JPanel contentStack = new JPanel(new BorderLayout());
        contentStack.setBackground(BG_DARK);
        contentStack.setLayout(new BorderLayout());

        mapPane = new MapPanel(satelliteName -> {
            selectedSatellite = satelliteName;
            mapPane.setSelectedSatellite(satelliteName);
            if (sidePanel != null) sidePanel.updateNotes(satelliteName);
            fetchTrajectory(satelliteName);
        });

        mapPane.setPreferredSize(new Dimension(0, 625));
        mapPane.setMinimumSize(new Dimension(800, 400));

        JPanel centerPanel = new JPanel();
        centerPanel.setPreferredSize(new Dimension(950, 30));
        centerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        centerPanel.setBackground(PANEL);

        JLabel latTitle = new JLabel(" LAT: ");
        latVal = new JLabel(" - ");
        JLabel lonTitle = new JLabel(" LON: ");
        lonVal = new JLabel(" - ");
        JLabel altTitle = new JLabel(" ALT: ");
        altVal = new JLabel(" - ");
        JLabel eclipsedTitle = new JLabel(" VIS: ");
        eclipsedVal = new JLabel(" - ");
        JLabel velocityTitle = new JLabel(" VEL : ");
        velVal = new JLabel(" - ");
        JLabel locationTitle = new JLabel(" LOC: ");
        locVal = new JLabel(" - "   );

        Font dataFont = new Font("Monospaced", Font.BOLD, 12);
        latVal.setFont(dataFont);
        latVal.setForeground(TEXT_SECONDARY);
        latTitle.setFont(dataFont);
        latTitle.setForeground(TEXT_PRIMARY);
        lonVal.setFont(dataFont);
        lonVal.setForeground(TEXT_SECONDARY);
        lonTitle.setFont(dataFont);
        lonTitle.setForeground(TEXT_PRIMARY);
        altVal.setFont(dataFont);
        altVal.setForeground(TEXT_SECONDARY);
        altTitle.setFont(dataFont);
        altTitle.setForeground(TEXT_PRIMARY);
        eclipsedVal.setFont(dataFont);
        eclipsedVal.setForeground(TEXT_SECONDARY);
        eclipsedTitle.setFont(dataFont);
        eclipsedTitle.setForeground(TEXT_PRIMARY);
        velVal.setFont(dataFont);
        velVal.setForeground(TEXT_SECONDARY);
        velocityTitle.setFont(dataFont);
        velocityTitle.setForeground(TEXT_PRIMARY);
        locVal.setFont(dataFont);
        locVal.setForeground(TEXT_SECONDARY);
        locationTitle.setFont(dataFont);
        locationTitle.setForeground(TEXT_PRIMARY);

        centerPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 12, 7));
        centerPanel.add(latTitle);
        centerPanel.add(latVal);
        centerPanel.add(new JLabel((" | ")));
        centerPanel.add(lonTitle);
        centerPanel.add(lonVal);
        centerPanel.add(new JLabel((" | ")));
        centerPanel.add(altTitle);
        centerPanel.add(altVal);
        centerPanel.add(new JLabel((" | ")));
        centerPanel.add(velocityTitle);
        centerPanel.add(velVal);
        centerPanel.add(new JLabel((" | ")));
        centerPanel.add(eclipsedTitle);
        centerPanel.add(eclipsedVal);
        centerPanel.add(new JLabel((" | ")));
        centerPanel.add(locationTitle);
        centerPanel.add(locVal);

        bottomPanel = new JPanel();
        bottomPanel.setPreferredSize(new Dimension(700, 500));
        bottomPanel.setBackground(BG_DARK);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));

        JScrollPane scrollPane = new JScrollPane(bottomPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBorder(null);
        scrollPane.setPreferredSize(new Dimension(950, 500));
        scrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 370));

        sidePanel = new SidePanel(selectedOrgs, fetchTask, minutes -> {
            if (selectedSatellite != null) fetchTrajectory(selectedSatellite);
        });
        JPanel sidePanelContent = sidePanel.buildPanel();
        sidePanelContent.setPreferredSize(new Dimension(250, 1000));
        sidePanelContent.setBackground(BG_DARK);

        JPanel bottomWrapper = new JPanel(new BorderLayout());
        bottomWrapper.setBackground(BG_DARK);
        bottomWrapper.add(centerPanel, BorderLayout.NORTH);
        bottomWrapper.add(scrollPane, BorderLayout.CENTER);

        bottomWrapper.setPreferredSize(new Dimension(1200,  335));
        bottomWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 400));

        contentStack.removeAll();
        contentStack.setLayout(new BoxLayout(contentStack, BoxLayout.Y_AXIS));
        contentStack.add(mapPane, BorderLayout.CENTER);
        contentStack.add(bottomWrapper, BorderLayout.SOUTH);

        frame.setLayout(new BorderLayout());
        frame.add(sidePanelContent, BorderLayout.WEST);
        frame.add(contentStack, BorderLayout.CENTER);

        frame.setVisible(true);

        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {

                double scale = 1.0 + ((frame.getWidth() - 1200) / 1200.0) * 0.75;
                scale = Math.clamp(scale, 1.0, 1.5);

                int newWidth = (int)(250 * scale);

                newWidth = Math.clamp(newWidth, 250, 500);

                sidePanelContent.setPreferredSize(new Dimension(newWidth, frame.getHeight()));
                sidePanel.scaleFonts(scale);

                frame.revalidate();
            }
        });
    }

    private void fetchTrajectory(String satelliteName) {
        SatelliteList.satellites().stream()
                .filter(s -> s.name().equals(satelliteName))
                .findFirst()
                .ifPresent(s -> new Thread(() -> {
                    try {
                        TLEData tle = tleFetcher.fetch(s.noradId());
                        List<double[]> trajectory = orbitPredictor.predict(tle, sidePanel.getTrajectoryMinutes(), 2);
                        mapPane.setTrajectory(trajectory);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }).start());
    }

    public void updatePosition(List<SatellitePosition> satPosition) {
        SwingUtilities.invokeLater(() -> {
            if (mapPane != null) {
                mapPane.updatePosition(satPosition);

                if (selectedSatellite == null) {
                    bottomPanel.removeAll();
                    bottomPanel.revalidate();
                    bottomPanel.repaint();
                    return;
                }

                List<Database.Satellite> historyChart = db.getHistory(selectedSatellite);

                if (historyChart.isEmpty()) {
                    bottomPanel.removeAll();
                    bottomPanel.revalidate();
                    bottomPanel.repaint();
                    return;
                }

                long interval = 15 * 60 * 1000L;

                Map<Long, List<Database.Satellite>> buckets = new TreeMap<>();

                for (Database.Satellite row : historyChart) {

                    long time = row.timeline().toInstant().toEpochMilli();

                    long bucket = Math.floorDiv(time, interval) * interval;

                    buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(row);
                }

                List<Long> sortedBuckets = new ArrayList<>(buckets.keySet());
                Collections.sort(sortedBuckets);

                List<Long> last7Buckets = sortedBuckets.subList(
                        Math.max(0, sortedBuckets.size() - 7),
                        sortedBuckets.size()
                );

                String[] labels = new String[last7Buckets.size()];
                SimpleDateFormat format = new SimpleDateFormat("HH:mm");

                for (int i = 0; i < last7Buckets.size(); i++) {
                    labels[i] = format.format(new Date(last7Buckets.get(i)));
                }

                XYSeries altitudeSeries = new XYSeries("Altitude");
                XYSeries velocitySeries = new XYSeries("Velocity");

                for (int i = 0; i < last7Buckets.size(); i++) {

                    long bucket = last7Buckets.get(i);
                    List<Database.Satellite> values = buckets.get(bucket);

                    double avgAlt = values.stream()
                            .mapToDouble(Database.Satellite::altitudeInKm)
                            .filter(a -> a > 100 && a < 50000)
                            .average()
                            .orElse(0);

                    double avgVel = values.stream()
                            .mapToDouble(Database.Satellite::velocity)
                            .filter(v -> v > 0 && v < 50000)
                            .average()
                            .orElse(0);

                    altitudeSeries.add(i, avgAlt);
                    velocitySeries.add(i, avgVel);
                }

                XYSeriesCollection altitudeDataset = new XYSeriesCollection();
                XYSeriesCollection velocityDataset = new XYSeriesCollection();

                altitudeDataset.addSeries(altitudeSeries);
                velocityDataset.addSeries(velocitySeries);

                bottomPanel.removeAll();

                bottomPanel.add(createChartPanel(
                        "Altitude",
                        "Altitude (km)",
                        altitudeDataset,
                        labels,
                        50
                ));

                bottomPanel.add(createChartPanel(
                        "Velocity",
                        "Velocity",
                        velocityDataset,
                        labels,
                        500
                ));

                bottomPanel.revalidate();
                bottomPanel.repaint();

                SatellitePosition display = satPosition.stream()
                        .filter(s -> s.name().equals(selectedSatellite))
                        .findFirst()
                        .orElse(satPosition.get(0));
                this.latVal.setText(String.format("%.4f", display.lat()));
                this.lonVal.setText(String.format("%.4f", display.lon()));
                this.altVal.setText(String.format("%.4f", display.alt()));
                this.eclipsedVal.setText(display.eclipsed() ? "eclipsed" : "daylight");

                long now = System.currentTimeMillis();

                history.addLast(display);
                timeHistory.addLast(now);

                if (history.size() > MAX_HISTORY) {
                    history.removeFirst();
                    timeHistory.removeFirst();
                }

                if (history.size() < 2) {
                    this.velVal.setText("calculating...");
                } else {
                    SatellitePosition oldest = history.getFirst();
                    SatellitePosition newest = history.getLast();

                    long tOld = timeHistory.getFirst();
                    long tNew = timeHistory.getLast();

                    double distance = distance3D(
                            oldest.lat(), oldest.lon(), oldest.alt(),
                            newest.lat(), newest.lon(), newest.alt()
                    );

                    double seconds = (tNew - tOld) / 1000.0;
                    double velocity = distance * (3600.0 / seconds);
                    double uncertainty = 0.08;

                    double lower = velocity * (1 - uncertainty);
                    double upper = velocity * (1 + uncertainty);

                    velVal.setText(String.format("%.2f km/h (±8%%)",
                            velocity, lower, upper));
                }

                previousTime = now;

                previousLat = display.lat();
                previousLon = display.lon();
                if (locVal != null) {
                    locVal.setText(
                            (display.location() != null && !display.location().isBlank()) ? display.location() : "ocean/sea"
                    );
                }
            }
        });
    }

    public double distance3D(double lat1, double lon1, double alt1,
                             double lat2, double lon2, double alt2) {

        double r1 = EARTH_RADIUS_KM + alt1;
        double r2 = EARTH_RADIUS_KM + alt2;

        double lat1Rad = Math.toRadians(lat1);
        double lon1Rad = Math.toRadians(lon1);
        double lat2Rad = Math.toRadians(lat2);
        double lon2Rad = Math.toRadians(lon2);

        double x1 = r1 * Math.cos(lat1Rad) * Math.cos(lon1Rad);
        double y1 = r1 * Math.cos(lat1Rad) * Math.sin(lon1Rad);
        double z1 = r1 * Math.sin(lat1Rad);

        double x2 = r2 * Math.cos(lat2Rad) * Math.cos(lon2Rad);
        double y2 = r2 * Math.cos(lat2Rad) * Math.sin(lon2Rad);
        double z2 = r2 * Math.sin(lat2Rad);

        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        lat1 = Math.toRadians(lat1);
        lat2 = Math.toRadians(lat2);

        double a = Math.pow(Math.sin(dLat / 2), 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                        Math.pow(Math.sin(dLon / 2), 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }
}

class MapPanel extends JPanel {

    private final JFXPanel fxPanel = new JFXPanel();
    private WebEngine webEngine;
    private boolean ready = false;
    private final Consumer<String> onSatelliteClicked;
    private List<SatellitePosition> pendingUpdate = null;
    private JavaBridge bridge;

    public MapPanel(Consumer<String> onSatelliteClicked) {
        this.onSatelliteClicked = onSatelliteClicked;
        setLayout(new BorderLayout());
        add(fxPanel, BorderLayout.CENTER);

        Platform.runLater(() -> {
            WebView webView = new WebView();
            webEngine = webView.getEngine();
            fxPanel.setScene(new Scene(webView));

            URL url = getClass().getResource("/map/map.html");
            if (url == null) { System.out.println("map.html not found"); return; }

            webEngine.load(url.toExternalForm());

            webEngine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == Worker.State.SUCCEEDED) {
                    webEngine.executeScript(
                            "console.log = function() {" +
                                    "  var args = Array.prototype.slice.call(arguments);" +
                                    "  window.javaBridge.log(args.join(' '));" +
                                    "};"
                    );
                    ready = true;

                    bridge = new JavaBridge();
                    JSObject window = (JSObject) webEngine.executeScript("window");
                    window.setMember("javaBridge", bridge);
                    webEngine.executeScript(
                            "window.onSatelliteClicked = function(name) { window.javaBridge.onClicked(name); };"
                    );

                    if (pendingUpdate != null) {
                        pushToJS(pendingUpdate);
                        pendingUpdate = null;
                    }
                }
            });
        });
    }

    public void setSelectedSatellite(String name) {
        if (!ready || webEngine == null) return;
        Platform.runLater(() ->
                webEngine.executeScript("window.setSelectedSatellite('" + name.replace("'", "") + "')"));
    }

    public void setTrajectory(List<double[]> trajectory) {
        if (!ready || webEngine == null) {
            return;
        }

        Platform.runLater(() -> {
            StringBuilder js = new StringBuilder();

            js.append("window.setTrajectory([");

            for (double[] p : trajectory) {
                js.append("{lat:")
                        .append(p[0])
                        .append(",lon:")
                        .append(p[1])
                        .append("},");
            }

            js.append("]);");

            System.out.println(js);
            webEngine.executeScript(js.toString());
        });
    }

    public class JavaBridge {
        public void onClicked(String name) {
//            System.out.println("JAVA: onClicked fired with: " + name);
            SwingUtilities.invokeLater(() -> onSatelliteClicked.accept(name));
        }

        public void log(String msg) {
            System.out.println("JS: " + msg);
        }
    }

    public void updatePosition(List<SatellitePosition> satPosition) {
        if (satPosition == null) return;

        Platform.runLater(() -> {
            if (!ready || webEngine == null) {
                pendingUpdate = satPosition; // queue it
                return;
            }
            pushToJS(satPosition);
        });
    }

    private void pushToJS(List<SatellitePosition> satPosition) {
        try {
            StringBuilder js = new StringBuilder("window.updateSatellites([");
            for (SatellitePosition s : satPosition) {
                js.append("{lat:").append(s.lat())
                        .append(",lon:").append(s.lon())
                        .append(",name:'").append(s.name().replace("'", "")).append("'},");
            }
            js.append("]);");
            webEngine.executeScript(js.toString());
        } catch (Exception e) {
            System.out.println("JS error: " + e.getMessage());
        }
    }
}