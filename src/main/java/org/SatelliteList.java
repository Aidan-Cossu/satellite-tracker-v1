package org;

import java.util.List;

public class SatelliteList {
    public static List<SatelliteRecord> satellites() {
        return List.of(
                new SatelliteRecord("ISS", "ISS",25544, "1998-067A", "421.7 km", "431.6 km", "51.6°", "93.0 minutes", "6797 km", "401.801 m2 (large)", "November 20, 1998", "International Space Station (ISS)", "TYURATAM MISSILE AND SPACE COMPLEX (TTMTR)"),
                new SatelliteRecord("Hubble", "NASA",20580, "1990-037B", "481.0 km", "483.5 km", "28.5°", "94.1 minutes", "6853 km", "28.5194 m2 (large)", "April 24, 1990", "United States (US)", "AIR FORCE EASTERN TEST RANGE (AFETR)"),
//                new SatelliteRecord("TERRA", "NASA",25994, "199-068A", "694.8 km", "698.9 km", "97.9°", "98.6 minutes", "7067 km", "13.0039 m2 (large)", "December 18, 1999", "United States (US)", "AIR FORCE WESTERN TEST RANGE (AFWTR)"),
//                new SatelliteRecord("AQUA", "NASA",27424, "2002-022A", "692.1 km", "695.1 km", "98.4°", "98.5 minutes", "7064 km", "74174 m2 (large)", "May 4, 2002", "United States (US)", "AIR FORCE WESTERN TEST RANGE (AFWTR)"),
                new SatelliteRecord("SENTINEL 1A", "ESA",39634, "2014-016A", "701.9", "703.9 km", "98.2°", "98.7 minutes", "7073 km", "Unknown", "April 3, 2014", "European Space Agency (ESA)", "FRENCH GUIANA (FRGUI"),
//                new SatelliteRecord("SENTINEL 2A", "ESA",40697, "2015-028A", "795.2 km", "797.0 km", "98.6°", "100.6 minutes", "7167 km", "Unknown", "June 23, 2015", "European Space Agency (ESA)", "FRENCH GUIANA (FRGUI)"),
//                new SatelliteRecord("OAO 3 (COPERNICUS)", "ESA",6153, "1972-65A", "695.1 km", "705.3 km", "35.0°", "98.6 minutes", "7071 km", "6.8136 m2 (large)", "August 21, 1972", "United States (US", "AIR FORCE EASTERN TEST RANGE (AFETR)"),
                new SatelliteRecord("GOES-R", "NOAA",41866, "2016-071A", "35,788.2 km", "35,799.4 km", "0.2°", "1,436.1 minutes", "42164 km", "Unknown", "November 19, 2016", "United States (US)", "AIR FORCE EASTERN TEST RANGE (AFETR)"),
//                new SatelliteRecord("STARLINK 1008", "SpaceX",44714, "2019-074B", "434.2 km", "435.4 km", "53.2°", "93.1 minutes", "6805 km", "Unknown", "November 11, 2019", "United States (US)", "AIR FORCE EASTERN TEST RANGE (AFETR)"),
                new SatelliteRecord("ALOS-4", "JAXA",60182, "2024-123A", "637.0 km", "639.2 km", "97.9°", "97.3 minutes", "7009 km", "Unknown", "uly 1, 2024", "Japan (JPN)", "TANEGASHIMA SPACE CENTER (TNSTA)")
        );
    }
}


