package org;

import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.SymbolAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The main window: side panel (filter, orbit slider, notes), the world map, a live info bar
 * and two history charts (altitude and velocity) for the selected satellite.
 *
 * Threading model. Swing is single-threaded, so all UI state is only touched on the
 * Event Dispatch Thread (EDT). Slow work (database queries, TLE download, orbit propagation)
 * runs on small background executors and posts its result back with
 * {@link SwingUtilities#invokeLater}.
 */
public class MapViewer {

    private static final double EARTH_RADIUS_KM = 6371.0088; // mean Earth radius

    // Charts show 7 buckets of 15 minutes each, built from the last 2 hours of data.
    private static final long CHART_INTERVAL = 15 * 60 * 1000L;
    private static final long CHART_REFRESH_MS = 60 * 1000L;
    private static final int CHART_HISTORY_HOURS = 2;
    private static final int CHART_BUCKETS = 7;

    private static final Color BG_DARK = new Color(11, 15, 26);
    private static final Color PANEL = new Color(18, 24, 38);
    private static final Color TEXT_PRIMARY = new Color(230, 237, 243);
    private static final Color TEXT_SECONDARY = new Color(154, 164, 178);

    private MapPanel mapPane;

    // Labels of the info bar under the map.
    private JLabel latVal;
    private JLabel lonVal;
    private JLabel altVal;
    private JLabel eclipsedVal;
    private JLabel velVal;
    private JLabel locVal;

    private JPanel bottomPanel;  // holds the two charts
    private SidePanel sidePanel;

    // EDT-only state.
    private String selectedSatellite;
    private Future<?> activeTrajectoryTask;
    private String lastChartSatellite;
    private long lastChartRefresh = -1;

    // Incremented for every trajectory request; results of older requests are discarded.
    private final AtomicLong trajectoryRequestId = new AtomicLong(0);

    // Daemon threads so they never keep the JVM alive after the window closes.
    private final ExecutorService chartExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Chart-Worker");
        thread.setDaemon(true);
        return thread;
    });

    private final ExecutorService trajectoryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Trajectory-Worker");
        thread.setDaemon(true);
        return thread;
    });

    private final Database db = new Database();
    private final TLEFetcher tleFetcher;
    private final OrbitPredictor orbitPredictor;

    public MapViewer(TLEFetcher tleFetcher, OrbitPredictor orbitPredictor) {
        this.tleFetcher = tleFetcher;
        this.orbitPredictor = orbitPredictor;
    }

    // ------------------------------------------------------------------ charts

    /** Builds one dark-themed line chart. The x axis shows time labels instead of numbers. */
    private ChartPanel createChartPanel(String title, String yAxisLabel, XYSeriesCollection dataset, String[] labels, double padding) {
        SymbolAxis xAxis = new SymbolAxis("Time (UTC)", labels);
        xAxis.setGridBandsVisible(false);
        xAxis.setTickLabelPaint(Color.WHITE);
        xAxis.setLabelPaint(Color.WHITE);
        xAxis.setAutoTickUnitSelection(false);
        xAxis.setVerticalTickLabels(false);
        xAxis.setLowerMargin(0.02);
        xAxis.setUpperMargin(0.02);

        NumberAxis yAxis = new NumberAxis(yAxisLabel);
        yAxis.setAutoRangeIncludesZero(false);
        yAxis.setTickLabelPaint(Color.WHITE);
        yAxis.setLabelPaint(Color.WHITE);

        // Compute the y range by hand so a little padding surrounds the data.
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (int s = 0; s < dataset.getSeriesCount(); s++) {
            XYSeries series = dataset.getSeries(s);

            for (int i = 0; i < series.getItemCount(); i++) {
                double value = series.getY(i).doubleValue();

                // Missing buckets are NaN; Math.min/max would turn the whole range into NaN.
                if (Double.isNaN(value)) {
                    continue;
                }

                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }

        if (min == Double.MAX_VALUE) {
            // No data at all: show an empty 0..1 axis.
            min = 0;
            max = 1;
        } else if (min == max) {
            // Flat line (e.g. a geostationary satellite): widen the range so it is not squashed.
            min -= padding;
            max += padding;
        } else {
            min = Math.max(0, min - padding);
            max += padding;
        }

        yAxis.setRange(min, max);

        // A plain line renderer draws NaN points as gaps (a spline renderer would break on them).
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, true);
        renderer.setSeriesPaint(0, Color.CYAN);
        renderer.setDefaultShapesVisible(true);

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

    private void clearCharts() {
        if (bottomPanel == null) {
            return;
        }

        bottomPanel.removeAll();
        bottomPanel.revalidate();
        bottomPanel.repaint();
    }

    /**
     * Reloads the chart data from the database. Throttled to once per minute unless forced
     * (for example when a different satellite is selected). The query runs off the EDT.
     */
    private void refreshCharts(String satelliteName, boolean force) {
        if (satelliteName == null) {
            clearCharts();
            return;
        }

        long nowMs = System.currentTimeMillis();

        if (!force && satelliteName.equals(lastChartSatellite) && lastChartRefresh >= 0 && nowMs - lastChartRefresh < CHART_REFRESH_MS) {
            return;
        }

        lastChartSatellite = satelliteName;
        lastChartRefresh = nowMs;

        chartExecutor.execute(() -> {
            try {
                ZonedDateTime since = ZonedDateTime.now(ZoneOffset.UTC).minusHours(CHART_HISTORY_HOURS);
                List<Database.Satellite> history = db.getRecentHistory(satelliteName, since);

                SwingUtilities.invokeLater(() -> {
                    // Ignore the result if the user selected another satellite in the meantime.
                    if (satelliteName.equals(selectedSatellite)) {
                        rebuildCharts(history);
                    }
                });

            } catch (Exception ex) {
                ex.printStackTrace();

                SwingUtilities.invokeLater(() -> {
                    if (satelliteName.equals(selectedSatellite)) {
                        clearCharts();
                        lastChartRefresh = -1; // allow a retry on the next tick
                    }
                });
            }
        });
    }

    /** Averages the rows into 15-minute buckets and replaces the two charts. Runs on the EDT. */
    private void rebuildCharts(List<Database.Satellite> history) {
        if (bottomPanel == null) {
            return;
        }

        if (history == null || history.isEmpty()) {
            // Nothing stored yet (just started or just selected): try again on the next tick.
            clearCharts();
            lastChartRefresh = -1;
            return;
        }

        // Group rows by the start of their 15-minute bucket (sorted by time).
        Map<Long, List<Database.Satellite>> buckets = new TreeMap<>();

        // Always show the last 7 buckets, even if some are empty, so gaps in the data stay visible.
        for (Database.Satellite row : history) {
            long time = row.timeline().toInstant().toEpochMilli();
            long bucket = Math.floorDiv(time, CHART_INTERVAL) * CHART_INTERVAL;
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(row);
        }

        long latestBucket = Math.floorDiv(System.currentTimeMillis(), CHART_INTERVAL) * CHART_INTERVAL;

        String[] labels = new String[CHART_BUCKETS];
        XYSeries altitudeSeries = new XYSeries("Altitude");
        XYSeries velocitySeries = new XYSeries("Velocity");

        SimpleDateFormat format = new SimpleDateFormat("HH:mm");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));

        for (int i = 0; i < CHART_BUCKETS; i++) {
            long bucket = latestBucket - (long) (CHART_BUCKETS - 1 - i) * CHART_INTERVAL;
            labels[i] = format.format(new Date(bucket));

            List<Database.Satellite> values = buckets.get(bucket);

            if (values == null || values.isEmpty()) {
                altitudeSeries.add(i, Double.NaN);
                velocitySeries.add(i, Double.NaN);
                continue;
            }

            // The filters drop obviously invalid values (e.g. 0 km/h from a failed calculation).
            altitudeSeries.add(i, values.stream()
                    .mapToDouble(Database.Satellite::altitudeInKm)
                    .filter(a -> a > 100 && a < 50000)
                    .average()
                    .orElse(Double.NaN));

            velocitySeries.add(i, values.stream()
                    .mapToDouble(Database.Satellite::velocity)
                    .filter(v -> v > 0 && v < 50000)
                    .average()
                    .orElse(Double.NaN));
        }

        XYSeriesCollection altitudeDataset = new XYSeriesCollection();
        XYSeriesCollection velocityDataset = new XYSeriesCollection();
        altitudeDataset.addSeries(altitudeSeries);
        velocityDataset.addSeries(velocitySeries);

        bottomPanel.removeAll();
        bottomPanel.add(createChartPanel("Altitude", "Altitude (km)", altitudeDataset, labels, 50));
        bottomPanel.add(createChartPanel("Velocity", "Velocity (km/h)", velocityDataset, labels, 500));
        bottomPanel.revalidate();
        bottomPanel.repaint();
    }

    // --------------------------------------------------------------- selection

    /** Called (on the EDT) when the user clicks a satellite on the map. */
    private void onSatelliteSelected(String satelliteName) {
        selectedSatellite = satelliteName;

        resetInfo();
        mapPane.setSelectedSatellite(satelliteName);
        mapPane.setTrajectory(List.of()); // clear the previous satellite's trajectory
        refreshCharts(satelliteName, true);

        if (sidePanel != null) {
            sidePanel.updateNotes(satelliteName);
        }

        fetchTrajectory(satelliteName);
    }

    private void resetInfo() {
        for (JLabel label : new JLabel[]{latVal, lonVal, altVal, eclipsedVal, velVal, locVal}) {
            if (label != null) {
                label.setText(" - ");
            }
        }
    }

    /**
     * Computes the predicted ground track on a background thread: download the TLE (cached),
     * propagate the orbit, and push the points to the map. If the user clicks again or moves
     * the slider meanwhile, the old request is cancelled and its result is ignored.
     */
    private void fetchTrajectory(String satelliteName) {
        // Read Swing state here on the EDT, not from the worker thread.
        int trajectoryMinutes = sidePanel.getTrajectoryMinutes();
        long requestId = trajectoryRequestId.incrementAndGet();

        if (activeTrajectoryTask != null) {
            activeTrajectoryTask.cancel(true);
        }

        SatelliteRecord satellite = SatelliteList.satellites().stream()
                .filter(s -> s.name().equals(satelliteName))
                .findFirst()
                .orElse(null);

        if (satellite == null) {
            return;
        }

        activeTrajectoryTask = trajectoryExecutor.submit(() -> {
            try {
                TLEData tle = tleFetcher.fetch(satellite.noradId());

                if (Thread.currentThread().isInterrupted() || requestId != trajectoryRequestId.get()) {
                    return;
                }

                // One point every 2 minutes is smooth enough for a line on a world map.
                List<double[]> trajectory = orbitPredictor.predict(tle, trajectoryMinutes, 2);

                if (Thread.currentThread().isInterrupted() || requestId != trajectoryRequestId.get()) {
                    return;
                }

                mapPane.setTrajectory(trajectory, () -> requestId == trajectoryRequestId.get());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

            } catch (Exception e) {
                if (requestId == trajectoryRequestId.get()) {
                    System.err.println("Failed to fetch trajectory for " + satelliteName + ": " + e.getMessage());
                }
            }
        });
    }

    // ------------------------------------------------------------------ window

    /** Builds and shows the window. Must be called on the EDT. */
    public void show(Set<String> selectedOrgs, FetchTask fetchTask) {
        mapPane = new MapPanel(this::onSatelliteSelected);

        JFrame frame = new JFrame("Satellite Tracker");
        frame.setSize(1200, 750);
        frame.setMinimumSize(new Dimension(900, 600));
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        URL iconUrl = getClass().getResource("/ISSIcon.png");
        if (iconUrl != null) {
            frame.setIconImage(new ImageIcon(iconUrl).getImage());
        }

        frame.setLayout(new BorderLayout());

        // Stop background work when the window closes.
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                trajectoryRequestId.incrementAndGet();

                if (activeTrajectoryTask != null) {
                    activeTrajectoryTask.cancel(true);
                }

                trajectoryExecutor.shutdownNow();
                chartExecutor.shutdownNow();
            }
        });

        mapPane.setMinimumSize(new Dimension(400, 300));

        Font dataFont = new Font("Monospaced", Font.BOLD, 12);

        latVal = valueLabel(dataFont);
        lonVal = valueLabel(dataFont);
        altVal = valueLabel(dataFont);
        velVal = valueLabel(dataFont);
        eclipsedVal = valueLabel(dataFont);
        locVal = valueLabel(dataFont);

        // Info bar: LAT | LON | ALT | VEL | VIS | LOC
        JPanel infoBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 7));
        infoBar.setPreferredSize(new Dimension(950, 30));
        infoBar.setBackground(PANEL);

        addInfo(infoBar, titleLabel(" LAT: ", dataFont), latVal, true);
        addInfo(infoBar, titleLabel(" LON: ", dataFont), lonVal, true);
        addInfo(infoBar, titleLabel(" ALT: ", dataFont), altVal, true);
        addInfo(infoBar, titleLabel(" VEL: ", dataFont), velVal, true);
        addInfo(infoBar, titleLabel(" VIS: ", dataFont), eclipsedVal, true);
        addInfo(infoBar, titleLabel(" LOC: ", dataFont), locVal, false);

        bottomPanel = new JPanel();
        bottomPanel.setBackground(BG_DARK);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));

        JScrollPane scrollPane = new JScrollPane(bottomPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBorder(null);

        JPanel bottomWrapper = new JPanel(new BorderLayout());
        bottomWrapper.setBackground(BG_DARK);
        bottomWrapper.add(infoBar, BorderLayout.NORTH);
        bottomWrapper.add(scrollPane, BorderLayout.CENTER);
        bottomWrapper.setPreferredSize(new Dimension(1200, 335));

        // BorderLayout: the map gets all the space the bottom panel does not need.
        JPanel contentStack = new JPanel(new BorderLayout());
        contentStack.setBackground(BG_DARK);
        contentStack.add(mapPane, BorderLayout.CENTER);
        contentStack.add(bottomWrapper, BorderLayout.SOUTH);

        sidePanel = new SidePanel(selectedOrgs, fetchTask, minutes -> {
            // Slider moved: recompute the trajectory for the selected satellite.
            if (selectedSatellite != null) {
                fetchTrajectory(selectedSatellite);
            }
        });

        JPanel sidePanelContent = sidePanel.buildPanel();
        sidePanelContent.setPreferredSize(new Dimension(250, 1000));
        sidePanelContent.setBackground(BG_DARK);

        frame.add(sidePanelContent, BorderLayout.WEST);
        frame.add(contentStack, BorderLayout.CENTER);

        frame.setVisible(true);

        // Grow the side panel and its fonts a little when the window is enlarged.
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // Math.clamp needs Java 21, so min/max is used for older Java versions.
                double scale = 1.0 + ((frame.getWidth() - 1200) / 1200.0) * 0.75;
                scale = Math.max(1.0, Math.min(scale, 1.5));

                int newWidth = Math.max(250, Math.min((int) (250 * scale), 500));

                sidePanelContent.setPreferredSize(new Dimension(newWidth, frame.getHeight()));
                sidePanel.scaleFonts(scale);

                frame.revalidate();
            }
        });
    }

    private JLabel valueLabel(Font font) {
        JLabel label = new JLabel(" - ");
        label.setFont(font);
        label.setForeground(TEXT_SECONDARY);
        return label;
    }

    private JLabel titleLabel(String text, Font font) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(TEXT_PRIMARY);
        return label;
    }

    private void addInfo(JPanel bar, JLabel title, JLabel value, boolean separator) {
        bar.add(title);
        bar.add(value);

        if (separator) {
            JLabel sep = new JLabel(" | ");
            sep.setForeground(TEXT_SECONDARY);
            bar.add(sep);
        }
    }

    // ----------------------------------------------------------------- updates

    /**
     * Receives the latest positions from {@link FetchTask} (called from a background thread),
     * moves the markers on the map and updates the info bar of the selected satellite.
     */
    public void updatePosition(List<SatellitePosition> satPosition, Map<String, Double> velocities) {
        SwingUtilities.invokeLater(() -> {
            if (mapPane == null) {
                return;
            }

            mapPane.updatePosition(satPosition);

            if (selectedSatellite == null) {
                return;
            }

            SatellitePosition display = satPosition.stream()
                    .filter(s -> s.name().equals(selectedSatellite))
                    .findFirst()
                    .orElse(null);

            if (display == null) {
                // The selected satellite is hidden by the organisation filter.
                resetInfo();
                return;
            }

            // Locale.ROOT: always a dot as decimal separator, regardless of the user's OS language.
            latVal.setText(String.format(Locale.ROOT, "%.4f", display.lat()));
            lonVal.setText(String.format(Locale.ROOT, "%.4f", display.lon()));
            altVal.setText(String.format(Locale.ROOT, "%.1f km", display.alt()));
            eclipsedVal.setText(display.eclipsed() ? "eclipsed" : "daylight");
            locVal.setText(display.location() != null && !display.location().isBlank() ? display.location() : "ocean/sea");

            Double velocity = velocities.get(display.name());
            velVal.setText(velocity == null || velocity <= 0 ? "calculating..." : String.format(Locale.ROOT, "%.0f km/h", velocity));

            refreshCharts(selectedSatellite, false);
        });
    }

    /**
     * Straight-line (chord) distance in km between two points given as latitude/longitude in
     * degrees and altitude in km. Both points are converted to 3D Cartesian coordinates around
     * the Earth's centre, which also accounts for the altitude difference.
     */
    public static double distance3D(double lat1, double lon1, double alt1,
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
}