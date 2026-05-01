package org;

import java.util.List;

public class SatelliteList {
    public static List<SatelliteRecord> satellites() {
        return List.of(
                new SatelliteRecord("ISS", "ISS",25544),
                new SatelliteRecord("Hubble", "NASA",20580),
                new SatelliteRecord("TERRA", "NASA",25994),
                new SatelliteRecord("AQUA", "NASA",27424),
                new SatelliteRecord("SENTINEL 1A", "ESA",39634),
                new SatelliteRecord("SENTINEL 2A", "ESA",40697),
                new SatelliteRecord("OAO 3 (COPERNICUS)", "ESA",6153),
                new SatelliteRecord("NOAA 18", "NOAA",28654),
                new SatelliteRecord("STARLINK 1008", "SpaceX",44714),
                new SatelliteRecord("ALOS-4", "JAXA",60182 )
        );
    }
}


