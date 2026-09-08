package org;

import org.orekit.bodies.GeodeticPoint;
import org.orekit.bodies.OneAxisEllipsoid;
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

public class OrbitPredictor {

    public List<double[]> predict(TLEData tleData, int minutesAhead, int intervalMinutes) {
        TLE tle = new TLE(tleData.line1(), tleData.line2());
        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle);

        List<double[]> points = new ArrayList<>();

        AbsoluteDate startDate = new AbsoluteDate(new Date(), TimeScalesFactory.getUTC());

        for (int i = 0; i <= minutesAhead; i += intervalMinutes) {
            AbsoluteDate date = startDate.shiftedBy(i * 60.0);
            SpacecraftState state = propagator.propagate(date);

            GeodeticPoint point = new OneAxisEllipsoid(
                    Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                    Constants.WGS84_EARTH_FLATTENING,
                    FramesFactory.getITRF(IERSConventions.IERS_2010, true)
            ).transform(state.getPVCoordinates().getPosition(),
                    FramesFactory.getEME2000(), date);

            points.add(new double[]{
                    Math.toDegrees(point.getLatitude()),
                    Math.toDegrees(point.getLongitude())
            });
        }

        return points;
    }
}
