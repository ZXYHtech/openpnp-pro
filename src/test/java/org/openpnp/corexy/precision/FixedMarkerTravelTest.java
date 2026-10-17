package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FixedMarkerTravelTest {
    static FixedMarkerSetup actualSetup() {
        FixedMarkerSetup setup = new FixedMarkerSetup();
        double[][] points = {{434.93716622923847, 335.91520462314315},
                {18.120629790767577, 292.4934297456781}, {43.903106348091164, 150.63372045441713},
                {401.41421371659965, 86.32885655342879}, {234.18457018585417, 148.60440233356564}};
        for (int i = 0; i < points.length; i++) {
            setup.markers.add(new FixedMarkerSetup.Marker(i == 0 ? "home" : "marker-" + (i + 1),
                    "Point " + (i + 1), points[i][0], points[i][1]));
        }
        return setup;
    }

    @Test
    void actualFivePointPlanAndStartupChecksFitAllStatesInsideTheRealTravel() {
        FixedMarkerSetup setup = actualSetup();
        CalibrationPlan plan = new CalibrationPlan(setup.toRequest());
        assertEquals("train-fixed-state-0-marker-2-0", plan.training.get(0).id);
        assertEquals(-28.879370209232423, plan.training.get(0).points(48)[0][0], 1e-9);
        List<CalibrationPlan.Path> paths = new ArrayList<>(plan.training);
        paths.addAll(plan.validation);
        paths.addAll(plan.finalCheck);
        paths.addAll(CalibrationPlan.fixedVerification(setup, 300));
        int fittedCount = 0;
        boolean shortenedStroke = false, fullLongStroke = false;
        for (CalibrationPlan.Path original : paths) {
            CalibrationPlan.Path fitted = FixedMarkerTravel.fit(original, 48, 1.200001, 450.799999, 1.200001, 452.799999);
            if (fitted != original) { fittedCount++; }
            shortenedStroke |= fitted.a < original.a;
            fullLongStroke |= fitted.a == 48;
            assertEquals(original.id, fitted.id);
            assertEquals(original.markerId, fitted.markerId);
            assertEquals(original.x, fitted.x);
            assertEquals(original.y, fitted.y);
            assertEquals(original.signA, fitted.signA);
            assertEquals(original.signB, fitted.signB);
            assertEquals(original.pieces, fitted.pieces);
            for (double[] point : fitted.points(48)) {
                assertTrue(point[0] >= 1.2 - 1e-9 && point[0] <= 450.8 + 1e-9, original.id);
                assertTrue(point[1] >= 1.2 - 1e-9 && point[1] <= 452.8 + 1e-9, original.id);
            }
            if (!original.homeStart && !original.direct) {
                double[][] points = fitted.points(48);
                double dx = points[points.length - 1][0] - points[1][0];
                double dy = points[points.length - 1][1] - points[1][1];
                assertEquals(fitted.signA * fitted.a, dx + dy, 1e-9);
                assertEquals(fitted.signB * fitted.b, dx - dy, 1e-9);
                double cx = points[1][0] - points[0][0], cy = points[1][1] - points[0][1];
                assertEquals(-fitted.signA * (Double.isFinite(fitted.conditioningTravel) ? fitted.conditioningTravel : 48), cx + cy, 1e-9);
                assertTrue(Math.abs(cx + cy) >= 3);
            }
        }
        assertTrue(fittedCount > 0);
        assertTrue(shortenedStroke);
        assertTrue(fullLongStroke, "Interior markers must retain full-length training data.");
    }

    @Test
    void insufficientTravelFailsWithoutMovingAnEndpointOrLosingAState() {
        CalibrationPlan.Path path = new CalibrationPlan.Path("boundary", "left", 1.2, 100,
                7, 5, .6, 1, 1, 1, 3, 300);
        assertThrows(IllegalStateException.class, () -> FixedMarkerTravel.fit(path, 48, 1.2, 450.8, 1.2, 452.8));
        CalibrationPlan.Path conditioning = new CalibrationPlan.Path("tight", "left", 2, 100,
                1, 1, .6, 1, -1, -1, 1, 300);
        assertThrows(IllegalStateException.class, () -> FixedMarkerTravel.fit(conditioning, 48, 1.2, 450.8, 1.2, 452.8));
    }
}