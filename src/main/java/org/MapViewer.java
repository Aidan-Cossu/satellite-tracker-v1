package org;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;

public class MapViewer {
    private static final double EARTH_RADIUS_KM = 6371.0088;
    private double previousLat;
    private double previousLon;
    private boolean firstUpdate = true;

    private static final Color BG_DARK = new Color(11, 15, 26);
    private static final Color PANEL = new Color(18, 24, 38);
    private static final Color ACCENT = new Color(63, 185, 255);
    private static final Color TEXT_PRIMARY = new Color(230, 237, 243);
    private static final Color TEXT_SECONDARY = new Color(154, 164, 178);

    private BackgroundPanel mapPane;
    private JLabel latVal;
    private JLabel lonVal;
    private JLabel altVal;
    private JLabel eclipsedVal;
    private JLabel velVal;
    private JLabel locVal;

    public void show(double lat, double lon, double alt, String name, boolean ecl, String loc) throws IOException{
        Image icon = new ImageIcon("ISSIcon.png").getImage();
        JFrame frame = new JFrame("Satellite Tracker");
        frame.setSize(1200, 1000);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setIconImage(icon);

        frame.setLayout(new BorderLayout());

        mapPane = new BackgroundPanel("worldmap.jpg", lat, lon, name);
        mapPane.setPreferredSize(new Dimension(1200, 625));

        JPanel centerPanel = new JPanel();
        centerPanel.setPreferredSize(new Dimension(1200, 75));
        centerPanel.setBackground(PANEL);

        JLabel latTitle = new JLabel(" LAT: ");
        latVal = new JLabel(String.format("%.4f", lat));
        JLabel lonTitle = new JLabel(" LON: ");
        lonVal = new JLabel(String.format("%.4f", lon));
        JLabel altTitle = new JLabel(" ALT: ");
        altVal = new JLabel(String.format("%.4f", alt));
        JLabel eclipsedTitle = new JLabel(" Vis: ");
        eclipsedVal = new JLabel(ecl ? "eclipsed" : "daylight");
        JLabel velocityTitle = new JLabel(" Vel: ");
        velVal = new JLabel(String.format("%.4f", haversine(previousLat, previousLon, lat, lon)));
        JLabel locationTitle = new JLabel(" Loc: ");
        locVal = new JLabel("ocean/sea");

        Font dataFont = new Font("Monospaced", Font.BOLD, 16);
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

        JPanel bottomPanel = new JPanel();
        bottomPanel.setPreferredSize(new Dimension(1200, 300));
        bottomPanel.setBackground(BG_DARK);

        frame.add(mapPane, BorderLayout.NORTH);
        frame.add(centerPanel, BorderLayout.CENTER);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        frame.setVisible(true);
    }

    public void updatePosition(double lat, double lon, double alt, String name, boolean eclipsed, String loc) {
        SwingUtilities.invokeLater(() -> {
            if (mapPane != null) {
                mapPane.updatePosition(lat, lon, name, eclipsed);
                this.latVal.setText(String.format("%.4f", lat));
                this.lonVal.setText(String.format("%.4f", lon));
                this.altVal.setText(String.format("%.4f", alt));
                this.eclipsedVal.setText(eclipsed ? "eclipsed" : "daylight");
                if (firstUpdate) {
                    this.velVal.setText(String.format("calculating..."));
                    firstUpdate = false;
                } else {
                    this.velVal.setText(String.format("%.4f", haversine(previousLat, previousLon, lat, lon)));
                }
                previousLat = lat;
                previousLon = lon;
                if (locVal != null) {
                    locVal.setText(
                            (loc != null && !loc.isBlank()) ? loc : "ocean/sea"
                    );
                }
            }
        });
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

        return EARTH_RADIUS_KM * c * 360;
    }
}

class BackgroundPanel extends JPanel {
    private final Image backgroundImage;
    private int x;
    private int y;
    private double latitude;
    private double longitude;
    private String satname;
    private Boolean satEclipsed;

    public BackgroundPanel(String filePath, double satlatitude, double satlongitude, String satname) throws IOException {
        backgroundImage = ImageIO.read(new File(filePath));
        this.latitude = satlatitude;
        this.longitude = satlongitude;
        this.satname = satname;
    }

    public void updatePosition(double lat, double lon, String name, Boolean eclipsed) {
        this.latitude = lat;
        this.longitude = lon;
        this.satname = name;
        this.satEclipsed = eclipsed;

        repaint();
    }

    public void setSatLatitude(Double satlatitude) {
        this.latitude = satlatitude;
    }

    public void setSatLongitude(Double satlongitude) {
        this.longitude = satlongitude;
    }

    public void setName(String satName) {
        this.satname = satName;
    }

    public void satEclipsed(Boolean satEclipsed) {
        this.satEclipsed = satEclipsed;
    }


    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), this);

        x = (int) ((longitude + 180) / 360 * getWidth());
        y = (int) ((90 - latitude) / 180 * getHeight());

        g.setColor(new Color(63, 185, 255, 150));
        g.fillOval(x - 7, y - 4, 18, 18);
        g.setFont(new Font("Arial", Font.BOLD, 12));
        g.setColor(Color.BLACK);
        g.drawString(satname, x + 16, y + 11);
        g.setColor(new Color(230, 237, 243));
        g.drawString(satname, x + 15, y + 10);
    }
}