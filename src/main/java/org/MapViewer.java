package org;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;

public class MapViewer {

    public void map(Double satlatitude, Double satlongitude, String satname) throws IOException{
        Image icon = new ImageIcon("ISSIcon.png").getImage();
        JFrame frame = new JFrame("Satellite Tracker");
        frame.setSize(1200, 800);
        frame.setContentPane(new BackgroundPanel("worldmap.png", satlatitude, satlongitude, satname));
        frame.setIconImage(icon);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }
}

class BackgroundPanel extends JPanel {
    private final Image backgroundImage;
    private int x;
    private int y;
    private double latitude;
    private double longitude;
    private String satname;

    public BackgroundPanel(String filePath, Double satlatitude, Double satlongitude, String satname) throws IOException {
        backgroundImage = ImageIO.read(new File(filePath));
        this.latitude = satlatitude;
        this.longitude = satlongitude;
        this.satname = satname;
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

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        g.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), this);

        x = (int) ((longitude + 180) / 360 * getWidth());
        y = (int) ((90 - latitude) / 180 * getHeight());

        g.setColor(Color.RED);
        g.fillOval(x, y, 10, 10);
        g.setFont(new Font("Arial", Font.BOLD, 12));
        g.setColor(Color.BLACK);
        g.drawString(satname, x + 16, y + 11);
        g.setColor(Color.WHITE);
        g.drawString(satname, x + 15, y + 10);
    }
}