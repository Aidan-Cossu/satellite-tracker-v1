package org;

import java.sql.*;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public class Database {

    private static final String DB_URL = "jdbc:sqlite:satellite-tracker.db";

    public void createTable() throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS SATELLITE (
                    SAT_ID          INTEGER PRIMARY KEY AUTOINCREMENT,
                    ID              INT             NOT NULL, 
                    NAME            VARCHAR(250)    NOT NULL, 
                    LATITUDE        DOUBLE,
                    LONGITUDE       DOUBLE,
                    ALTITUDE_IN_KM  DOUBLE,
                    TIMELINE        TEXT,
                    VELOCITY        DOUBLE,
                    VISIBILITY      CHAR(15),
                    LOCATION        VARCHAR(120)
                )
            """;
        try (Connection connection = DriverManager.getConnection(DB_URL);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
//            System.out.println("Table created successfully");
        } catch (SQLException e) {
                 e.printStackTrace();
        }
    }

    public void savePosition(int ID, String name, double latitude, double longitude, double altitude, ZonedDateTime timeLine, double velocity, String visibility, String location) {

        String sql = "INSERT INTO SATELLITE(ID, NAME, LATITUDE, LONGITUDE, ALTITUDE_IN_KM, TIMELINE, VELOCITY, VISIBILITY, LOCATION) VALUES(?,?,?,?,?,?,?,?,?)";

        try (var conn = DriverManager.getConnection(DB_URL);
        var pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, ID);
            pstmt.setString(2, name);
            pstmt.setDouble(3, latitude);
            pstmt.setDouble(4, longitude);
            pstmt.setDouble(5, altitude);
            pstmt.setObject(6, timeLine.toString());
            pstmt.setObject(7, velocity);
            pstmt.setObject(8, visibility);
            pstmt.setObject(9, location);
            pstmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    public record Satellite(ZonedDateTime timeline, double altitudeInKm, double velocity) {}

    public List<Satellite> getHistory(String satelliteName) {
        List<Satellite> recordList = new ArrayList<>();

        String sql = """
                SELECT TIMELINE, ALTITUDE_IN_KM, VELOCITY
                FROM SATELLITE
                WHERE NAME = ?
                ORDER BY TIMELINE DESC
                """;

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, satelliteName);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ZonedDateTime timeline = ZonedDateTime
                            .parse(rs.getString("TIMELINE"))
                            .withNano(0);
                    double altitudeInKm = rs.getDouble("ALTITUDE_IN_KM");
                    double velocity = rs.getDouble("VELOCITY");

                    Satellite satelliteRecord = new Satellite(timeline, altitudeInKm, velocity);

                    recordList.add(satelliteRecord);
                }
            }
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }

        return recordList;
    }
}