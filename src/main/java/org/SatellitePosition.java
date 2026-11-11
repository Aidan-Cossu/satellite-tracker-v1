package org;

/**
 * A snapshot of one satellite at one moment, as shown on the map and in the info bar.
 *
 * @param noradId  NORAD catalogue number (unique satellite id)
 * @param name     display name from {@link SatelliteList}
 * @param lat      latitude in degrees
 * @param lon      longitude in degrees
 * @param alt      altitude above the Earth's surface in km
 * @param eclipsed true if the satellite is in Earth's shadow, false if it is sunlit
 * @param location nearest place name (city, country or "ocean/sea")
 */
public record SatellitePosition(int noradId, String name, double lat, double lon, double alt, boolean eclipsed, String location) {}