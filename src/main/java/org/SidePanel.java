package org;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Left-hand control panel. It has two "cards" that are swapped with a {@link CardLayout}:
 *
 * main: show-all / filter buttons, the orbit-prediction slider and a notes box
 * describing the selected satellite.
 * filter: one toggle per organisation (ISS, NASA, ESA, ...). Selecting
 * organisations limits which satellites are fetched and shown.
 *
 * Fonts and row heights are scaled when the window is resized (see {@link #scaleFonts(double)}).
 */
public class SidePanel {
    private static final Color BG_DARK = new Color(11, 15, 26);
    private static final Color PANEL = new Color(18, 24, 38);
    private static final Color ACCENT = new Color(63, 185, 255);
    private static final Color TEXT_PRIMARY = new Color(230, 237, 243);
    private static final Color TEXT_SECONDARY = new Color(154, 164, 178);

    // Shared with FetchTask: an empty set means "no filter, show everything".
    private final Set<String> selectedOrgs;
    private final FetchTask fetchTask;

    // Remembered so scaleFonts() can resize them later.
    private final List<JLabel> allLabels = new ArrayList<>();
    private final List<JButton> allButtons = new ArrayList<>();
    private final List<JPanel> allPanels = new ArrayList<>();

    private JTextArea notesArea;
    private JLabel notesTitle;

    private JLabel sliderNote;
    private JSlider slider;

    // Called with the chosen prediction length in minutes when the slider is released.
    private final Consumer<Integer> onSliderChanged;

    private JLabel styledLabel(String text, Font font) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(TEXT_SECONDARY);
        return label;
    }

    public SidePanel(Set<String> selectedOrgs, FetchTask fetchTask, Consumer<Integer> onSliderChanged) {
        this.selectedOrgs = selectedOrgs;
        this.fetchTask = fetchTask;
        this.onSliderChanged = onSliderChanged;
    }

    /** Builds the whole panel. Must be called on the EDT. */
    public JPanel buildPanel () {
        Font dataFont = new Font("Monospaced", Font.BOLD, 12);

        // Distinct organisation names, in the order they appear in SatelliteList.
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

        JButton showAll = new JButton("show all");
        JButton btnPrev = new JButton("go back");
        JButton btnShowFilter = new JButton("filter");

        allButtons.add(showAll);
        allButtons.add(btnPrev);
        allButtons.add(btnShowFilter);

        // --- Notes box -------------------------------------------------------------------
        notesTitle = new JLabel("NOTES:");
        notesTitle.setFont(dataFont);
        notesTitle.setForeground(TEXT_SECONDARY);
        notesTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        notesTitle.setBorder(BorderFactory.createEmptyBorder(16, 0, 4, 0));

        notesArea = new JTextArea("Select a satellite to see notes.");
        notesArea.setWrapStyleWord(true);
        notesArea.setLineWrap(true);
        notesArea.setEditable(false);
        notesArea.setBackground(BG_DARK);
        notesArea.setForeground(TEXT_SECONDARY);
        notesArea.setFont(dataFont);
        notesArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        notesArea.setMaximumSize(new Dimension(290, 120));

        // --- Orbit prediction slider (1-24 hours) -----------------------------------------
        sliderNote = new JLabel("⬇ orbit prediction ⬇");
        sliderNote.setFont(dataFont);
        sliderNote.setForeground(ACCENT);
        sliderNote.setBackground(PANEL);
        sliderNote.setOpaque(true);
        sliderNote.setHorizontalAlignment(SwingConstants.CENTER);
        sliderNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        sliderNote.setMaximumSize(new Dimension(250, 40));
        sliderNote.setPreferredSize(new Dimension(250, 40));

        slider = new JSlider(JSlider.HORIZONTAL, 1, 24, 3);

        slider.setPaintTicks(true);
        slider.setPaintLabels(true);

        slider.setBackground(BG_DARK);
        slider.setForeground(TEXT_PRIMARY);
        slider.setFont(dataFont);
        slider.setAlignmentX(Component.LEFT_ALIGNMENT);
        slider.setMaximumSize(new Dimension(400, 60));
        slider.setOpaque(false);

        slider.setForeground(TEXT_SECONDARY);

        slider.setSnapToTicks(true);
        slider.setMinorTickSpacing(1);

        Hashtable<Integer, JLabel> labelTable = new Hashtable<>();
        labelTable.put(1, styledLabel("1h", dataFont));
        labelTable.put(6, styledLabel("6h", dataFont));
        labelTable.put(12, styledLabel("12h", dataFont));
        labelTable.put(18, styledLabel("18h", dataFont));
        labelTable.put(24, styledLabel("24h", dataFont));
        slider.setLabelTable(labelTable);
        slider.setPaintLabels(true);
        slider.setMajorTickSpacing(6);
        slider.setMinorTickSpacing(1);

        // Only react when the user lets go of the slider, not on every pixel of movement,
        // because each change triggers an orbit calculation.
        slider.addChangeListener(e -> {
            if (!slider.getValueIsAdjusting()) {
                onSliderChanged.accept(slider.getValue() * 60);
            }
        });

        // --- Filter card: one clickable row per organisation ------------------------------
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
            orgLabel.setHorizontalAlignment(SwingConstants.CENTER);

            // mousePressed instead of mouseClicked: "clicked" is lost if the mouse moves slightly.
            orgPanel.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    // Toggle: highlighted = included in the filter.
                    if (selectedOrgs.contains(org)) {
                        selectedOrgs.remove(org);
                        orgPanel.setBackground(PANEL);
                        orgLabel.setForeground(TEXT_PRIMARY);
                    } else {
                        selectedOrgs.add(org);
                        orgPanel.setBackground(ACCENT);
                        orgLabel.setForeground(BG_DARK);
                    }
                    // Refresh right away, off the EDT, so the map reflects the filter immediately.
                    new Thread(() -> fetchTask.fetchNow()).start();
                }
            });

            orgPanels.add(orgPanel);
            orgLabels.add(orgLabel);

            allPanels.add(orgPanel);
            allLabels.add(orgLabel);

            filter.add(orgPanel);
        }

        // "show all" clears the filter and un-highlights every organisation.
        showAll.addActionListener(e -> {
            selectedOrgs.clear(); // clear first, then fetch, so the fetch sees the empty filter
            new Thread(() -> fetchTask.fetchNow()).start();
            for (int i = 0; i < orgPanels.size(); i++) {
                orgPanels.get(i).setBackground(PANEL);
                orgLabels.get(i).setForeground(TEXT_PRIMARY);
            }
            cardLayout.show(cardPanel, "main");
        });

        // --- Button styling ----------------------------------------------------------------
        showAll.setFont(dataFont);

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

        main.add(showAll);
        main.add(btnShowFilter);
        main.add(sliderNote);
        main.add(slider);
        main.add(notesTitle);
        main.add(notesArea);

        return cardPanel;
    }

    /** Current slider value converted to minutes. */
    public int getTrajectoryMinutes() {
        return  slider.getValue() * 60;
    }

    /** Scales fonts and row heights (scale 1.0 = default size) when the window is enlarged. */
    public void scaleFonts(double scale) {
        int fontSize = Math.max(12, (int)(12 * scale));
        Font font = new Font("Monospaced", Font.BOLD, fontSize);

        int rowHeight = Math.max(40, (int)(40 * scale));

        for (JButton b : allButtons) {
            b.setFont(font);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowHeight));
            b.setPreferredSize(new Dimension(0, rowHeight));
        }

        sliderNote.setFont(font);
        sliderNote.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowHeight));
        sliderNote.setPreferredSize(new Dimension(0, rowHeight));

        for (JLabel l : allLabels) {
            l.setFont(font);
        }

        for (JPanel p : allPanels) {
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowHeight));
            p.setPreferredSize(new Dimension(0, rowHeight));
        }
    }

    /** Shows the notes of the given satellite (see the last field of {@link SatelliteRecord}). */
    public void updateNotes(String satelliteName) {
        SatelliteList.satellites().stream()
                .filter(s -> s.name().equals(satelliteName))
                .findFirst()
                .ifPresent(s -> SwingUtilities.invokeLater(() -> {
                    String notes = s.notes();
                    notesArea.setText(notes.isBlank() ? "No notes available." : notes);
                }));
    }
}
