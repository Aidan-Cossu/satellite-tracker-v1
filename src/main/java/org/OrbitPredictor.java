package org;

import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Predicts a satellite's future ground track using the Orekit library.
 *
 * Pipeline: a TLE (two-line element set, the standard compact orbit description) is turned
 * into a propagator (SGP4/SDP4); for every time step the propagator gives the satellite's
 * position in space, which is then converted to latitude/longitude on the WGS84 Earth ellipsoid.
 *
 * Orekit needs its data folder (leap seconds, Earth orientation parameters) to be registered
 * before use; this is done in {@link Main}.
 */
public class OrbitPredictor {

    /**
     * @param tleData         orbit description for the satellite
     * @param minutesAhead    how far into the future to predict, starting now
     * @param intervalMinutes spacing between returned points
     * @return list of {latitude, longitude} pairs in degrees
     */
    public List<double[]> predict(TLEData tleData, int minutesAhead, int intervalMinutes) {
        if (intervalMinutes <= 0) {
            throw new IllegalArgumentException("intervalMinutes must be > 0");
        }

        TLE tle = new TLE(tleData.line1(), tleData.line2());
        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);

        // The Earth model is created once, outside the loop (creating it is relatively expensive).
        // ITRF is an Earth-fixed frame, so longitudes come out relative to the rotating Earth.
        Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);
        OneAxisEllipsoid earth = new OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS, Constants.WGS84_EARTH_FLATTENING, itrf);

        AbsoluteDate startDate = new AbsoluteDate(new Date(), TimeScalesFactory.getUTC());
        List<double[]> points = new ArrayList<>();

        for (int i = 0; i <= minutesAhead; i += intervalMinutes) {
            AbsoluteDate date = startDate.shiftedBy(i * 60.0);
            SpacecraftState state = propagator.propagate(date);

            // TLE propagation produces positions in the TEME frame, so the state's own frame is
            // used for the conversion. Assuming a different frame (e.g. EME2000) would shift the
            // ground track by tens of kilometres.
            GeodeticPoint point = earth.transform(state.getPVCoordinates().getPosition(), state.getFrame(), date);

            points.add(new double[]{
                    Math.toDegrees(point.getLatitude()),
                    Math.toDegrees(point.getLongitude())
            });
        }

        return points;
    }
}