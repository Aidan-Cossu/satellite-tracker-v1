package org;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite storage for the position history that feeds the altitude and velocity charts.
 *
 * Design notes:
 *
 *  Timestamps are stored as fixed-width UTC text ({@code 2026-01-31T12:00:00.000Z}), so
 *  sorting by the text column is the same as sorting by time and DST never matters.
 *  WAL mode lets the UI read while the background thread writes.
 *  Rows older than {@link #RETENTION_DAYS} are deleted so the file does not grow forever.
 */
public class Database {
    // Relative path: the file is created in the directory the app is started from.
    private static final String DB_URL = "jdbc:sqlite:satellite-tracker.db";

    private static final int RETENTION_DAYS = 7;

    private static final DateTimeFormatter DB_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** One row to insert. */
    public record PositionData(int id, String name, double latitude, double longitude, double altitude,
                               ZonedDateTime timeline, double velocity, String visibility, String location) {}

    /** The subset of a row the charts need. */
    public record Satellite(ZonedDateTime timeline, double altitudeInKm, double velocity) {}

    /** Creates the table and its indexes if they do not exist yet. */
    public void createTable() throws SQLException {
        enableWalMode();

        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {

            statement.execute("""
                CREATE TABLE IF NOT EXISTS SATELLITE (
                    SAT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    ID INT NOT NULL,
                    NAME VARCHAR(250) NOT NULL,
                    LATITUDE DOUBLE,
                    LONGITUDE DOUBLE,
                    ALTITUDE_IN_KM DOUBLE,
                    TIMELINE TEXT NOT NULL,
                    VELOCITY DOUBLE,
                    VISIBILITY CHAR(15),
                    LOCATION VARCHAR(120)
                )
                """);

            // Used by the chart query (filter by satellite, then by time).
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sat_name_timeline ON SATELLITE(NAME, TIMELINE)");

            // Used by the retention DELETE, which filters on time only.
            statement.execute("CREATE INDEX IF NOT EXISTS idx_sat_timeline ON SATELLITE(TIMELINE)");
        }
    }

    /** Inserts all rows in a single transaction (much faster than one commit per row). */
    public void savePositions(List<PositionData> positions) throws SQLException {
        if (positions.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO SATELLITE
            (ID, NAME, LATITUDE, LONGITUDE, ALTITUDE_IN_KM, TIMELINE, VELOCITY, VISIBILITY, LOCATION)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = connect()) {
            conn.setAutoCommit(false);

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (PositionData position : positions) {
                    pstmt.setInt(1, position.id());
                    pstmt.setString(2, position.name());
                    pstmt.setDouble(3, position.latitude());
                    pstmt.setDouble(4, position.longitude());
                    pstmt.setDouble(5, position.altitude());
                    pstmt.setString(6, DB_TIME_FORMAT.format(position.timeline().toInstant()));
                    pstmt.setDouble(7, position.velocity());
                    pstmt.setString(8, position.visibility());
                    pstmt.setString(9, position.location());
                    pstmt.addBatch();
                }

                pstmt.executeBatch();
                conn.commit();

            } catch (SQLException e) {
                // All-or-nothing: it never leaves a half-written batch behind.
                conn.rollback();
                throw e;
            }
        }
    }

    /** Returns all rows of one satellite newer than {@code since}, oldest first. */
    public List<Satellite> getRecentHistory(String satelliteName, ZonedDateTime since) throws SQLException {
        List<Satellite> recordList = new ArrayList<>();

        String sql = """
            SELECT TIMELINE, ALTITUDE_IN_KM, VELOCITY
            FROM SATELLITE
            WHERE NAME = ?
              AND TIMELINE > ?
            ORDER BY TIMELINE ASC
            """;

        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, satelliteName);
            pstmt.setString(2, DB_TIME_FORMAT.format(since.toInstant()));

            ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    recordList.add(new Satellite(
                            ZonedDateTime.parse(rs.getString("TIMELINE")),
                            rs.getDouble("ALTITUDE_IN_KM"),
                            rs.getDouble("VELOCITY")));
                }
            }

        return recordList;
    }

    /** Retention policy: removes rows older than {@link #RETENTION_DAYS} days. */
    public void deleteOldPositions() {
        String sql = "DELETE FROM SATELLITE WHERE TIMELINE < ?";

        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            ZonedDateTime cutoff = ZonedDateTime.now(ZoneOffset.UTC).minusDays(RETENTION_DAYS);
            pstmt.setString(1, DB_TIME_FORMAT.format(cutoff.toInstant()));

            int deleted = pstmt.executeUpdate();
            System.out.println("Deleted " + deleted + " old satellite records.");

        } catch (SQLException e) {
            System.err.println("Failed to delete old positions: " + e.getMessage());
        }
    }

    /**
     * Opens a connection with a busy timeout, so that a read that overlaps a write waits up to
     * 5 seconds instead of failing immediately with "database is locked".
     */
    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(DB_URL);

        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000;");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }

        return connection;
    }

    /** Write-ahead logging: readers do not block the writer and vice versa. It is stored in the file. */
    private void enableWalMode() throws SQLException {
        try (Connection connection = DriverManager.getConnection(DB_URL);
             Statement statement = connection.createStatement()) {

            statement.execute("PRAGMA journal_mode=WAL;");
        }
    }
}