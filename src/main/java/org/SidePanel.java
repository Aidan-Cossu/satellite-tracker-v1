package org;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SidePanel {
    private static final Color BG_DARK = new Color(11, 15, 26);
    private static final Color PANEL = new Color(18, 24, 38);
    private static final Color ACCENT = new Color(63, 185, 255);
    private static final Color TEXT_PRIMARY = new Color(230, 237, 243);
    private static final Color TEXT_SECONDARY = new Color(154, 164, 178);

    private final Set<String> selectedOrgs;
    private final FetchTask fetchTask;

    public SidePanel(Set<String> selectedOrgs, FetchTask fetchTask) {
        this.selectedOrgs = selectedOrgs;
        this.fetchTask = fetchTask;
    }

    public JPanel buildPanel () {
        Font dataFont = new Font("Monospaced", Font.BOLD, 12);

        List<String> organisation = SatelliteList.satellites().stream()
                .map(SatelliteRecord::organisation)
                .distinct()
                .collect(Collectors.toList());

        CardLayout cardLayout = new CardLayout();
        JPanel cardPanel = new JPanel(cardLayout);

        JPanel main = new JPanel();
        main.setBackground(BG_DARK);
        main.setMaximumSize(new Dimension(250, 40));

        JPanel filter = new JPanel();
        filter.setBackground(BG_DARK);

        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        filter.setLayout(new BoxLayout(filter, BoxLayout.Y_AXIS));

        cardPanel.add(main, "main");
        cardPanel.add(filter, "filter");

        JPanel buttonPanel = new JPanel();
        JButton showAll = new JButton("show all");
        JButton btnPrev = new JButton("go back");
        JButton btnShowFilter = new JButton("filter");

        main.add(showAll);

        filter.add(btnPrev);

        List<JPanel> orgPanels = new ArrayList<>();
        List<JLabel> orgLabels = new ArrayList<>();

        for (String org : organisation) {
            JPanel orgPanel = new JPanel(new BorderLayout());
            orgPanel.setBackground(PANEL);
            orgPanel.setMaximumSize(new Dimension(250, 40));
            orgPanel.setCursor(new Cursor(Cursor.HAND_CURSOR));

            JLabel orgLabel = new JLabel(org);
            orgLabel.setFont(dataFont);
            orgLabel.setForeground(TEXT_PRIMARY);
            orgPanel.add(orgLabel, BorderLayout.CENTER);
            orgPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            orgLabel.setHorizontalAlignment(SwingUtilities.CENTER);

            orgPanel.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (selectedOrgs.contains(org)) {
                        selectedOrgs.remove(org);
                        orgPanel.setBackground(PANEL);
                        orgLabel.setForeground(TEXT_PRIMARY);
                    } else {
                        selectedOrgs.add(org);
                        orgPanel.setBackground(ACCENT);
                        orgLabel.setForeground(BG_DARK);
                    }
                    new Thread(() -> fetchTask.fetchNow()).start();
                }
            });

            orgPanels.add(orgPanel);
            orgLabels.add(orgLabel);
            filter.add(orgPanel);
        }

        showAll.addActionListener(e -> {
            new Thread(() -> fetchTask.fetchNow()).start();
            selectedOrgs.clear();
            for (int i = 0; i < orgPanels.size(); i++) {
                orgPanels.get(i).setBackground(PANEL);
                orgLabels.get(i).setForeground(TEXT_PRIMARY);
            }
            cardLayout.show(cardPanel, "main");
        });

        btnPrev.setBorderPainted(false);
        btnShowFilter.setBorderPainted(false);
        showAll.setBorderPainted(false);

        btnPrev.setFocusPainted(false);
        btnShowFilter.setFocusPainted(false);
        showAll.setFocusPainted(false);

        btnPrev.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnShowFilter.setCursor(new Cursor(Cursor.HAND_CURSOR));
        showAll.setCursor(new Cursor(Cursor.HAND_CURSOR));

        btnShowFilter.addActionListener(e -> cardLayout.show(cardPanel, "filter"));
        btnPrev.addActionListener(e -> cardLayout.show(cardPanel, "main"));

        btnPrev.setFont(dataFont);
        btnPrev.setForeground(ACCENT);
        showAll.setForeground(ACCENT);
        btnShowFilter.setFont(dataFont);
        btnShowFilter.setForeground(ACCENT);
        showAll.setForeground(ACCENT);

        btnPrev.setBackground(PANEL);
        btnShowFilter.setBackground(PANEL);
        showAll.setBackground(PANEL);

        btnShowFilter.setMaximumSize(new Dimension(250, 40));
        btnPrev.setMaximumSize(new Dimension(250, 40));
        showAll.setMaximumSize(new Dimension(250, 40));

        btnPrev.setAlignmentX(Component.LEFT_ALIGNMENT);
        showAll.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnShowFilter.setAlignmentX(Component.LEFT_ALIGNMENT);

        main.add(btnShowFilter);

        return cardPanel;
    }
}
