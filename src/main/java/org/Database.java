package org;

import java.sql.*;
import java.time.ZonedDateTime;
import java.util.TimeZone;

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
                    TIMELINE        TEXT
                )
            """;
        try (Connection connection = DriverManager.getConnection(DB_URL);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            System.out.println("Table created successfully");
        } catch (SQLException e) {
                 e.printStackTrace();
        }
    }

    public void savePosition(int ID, String name, double latitude, double longitude, double altitude, ZonedDateTime timeLine) {

        String sql = "INSERT INTO SATELLITE(ID, NAME, LATITUDE, LONGITUDE, ALTITUDE_IN_KM, TIMELINE) VALUES(?,?,?,?,?,?)";

        try (var conn = DriverManager.getConnection(DB_URL);
        var pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, ID);
            pstmt.setString(2, name);
            pstmt.setDouble(3, latitude);
            pstmt.setDouble(4, longitude);
            pstmt.setDouble(5, altitude);
            pstmt.setObject(6, timeLine.toString());
            pstmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }
}