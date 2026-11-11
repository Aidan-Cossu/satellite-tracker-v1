package org;

/**
 * Static information about a tracked satellite. The orbital values are plain display text
 * (they are shown to the user, never used in calculations); live positions come from the API.
 *
 * @param name                   display name, also used as the key for selection and DB queries
 * @param organisation           group used by the filter panel (e.g. "NASA", "ESA")
 * @param noradId                NORAD catalogue number used for all API requests
 * @param internationalDesignator COSPAR id: launch year, launch number and piece
 * @param notes                  free text shown in the side panel (may be empty)
 */
public record SatelliteRecord (String name, String organisation, int noradId,String internationalDesignator,
                               String PERIGEE, String APOGEE, String INCLINATION, String PERIOD, String SEMI_MAJOR_AXIS,
                               String RCS, String LAUNCH_DATE, String SOURCE, String LAUNCH_SITE, String notes) {}