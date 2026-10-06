package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

class BottomVisionTrialCorrectionTest {
    private static final double EPSILON = 1e-9;

    @Test
    void subtractsZeroDegreePlacementErrorFromVisionOffset() {
        BottomVisionTrialCorrection.Result result = BottomVisionTrialCorrection.calculate(
                mm(0.20, -0.10, 0), mm(100, 200, 0), mm(100.30, 199.80, 0));

        assertLocation(-0.10, 0.10, result.newOffset);
        assertLocation(0.30, -0.20, result.globalError);
        assertLocation(0.30, -0.20, result.localError);
    }

    @Test
    void inverseRotatesNinetyDegreePlacementErrorBeforeApplying() {
        BottomVisionTrialCorrection.Result result = BottomVisionTrialCorrection.calculate(
                mm(0, 0, 0), mm(10, 20, 90), mm(10.40, 20.20, 0));

        assertLocation(-0.20, 0.40, result.newOffset);
        assertLocation(0.20, -0.40, result.localError);
    }

    @Test
    void handlesNegativeAndOneHundredEightyDegreeErrors() {
        BottomVisionTrialCorrection.Result result = BottomVisionTrialCorrection.calculate(
                mm(0.10, 0.10, 0), mm(0, 0, 180), mm(-0.25, 0.35, 0));

        assertLocation(-0.15, 0.45, result.newOffset);
        assertEquals(Math.hypot(0.25, 0.35), result.getMagnitudeMm(), EPSILON);
    }

    @Test
    void cancelsTheSameGlobalErrorAtHorizontalVerticalAndArbitraryAngles() {
        for (double rotation : new double[] { 0, 90, -90, 180, 37.5 }) {
            BottomVisionTrialCorrection.Result result = BottomVisionTrialCorrection.calculate(
                    mm(0.12, -0.08, 0), mm(50, 75, rotation), mm(50.32, 74.81, 0));

            assertLocation(-result.globalError.getX(), -result.globalError.getY(),
                    result.getAppliedGlobalShift());
        }
    }

    @Test
    void readsBackSavedVisionOffset(@TempDir File directory) throws Exception {
        BottomVisionSettings settings = new BottomVisionSettings("BVS_TRIAL_TEST");
        settings.setVisionOffset(mm(-0.123, 0.456, 0));
        Configuration.VisionSettingsConfigurationHolder holder =
                new Configuration.VisionSettingsConfigurationHolder();
        holder.visionSettings.add(settings);
        File file = new File(directory, "vision-settings.xml");
        Configuration.createSerializer().write(holder, file);

        BottomVisionTrialCorrection.verifySavedOffset(file, settings.getId(),
                mm(-0.123, 0.456, 0));
    }

    private static Location mm(double x, double y, double rotation) {
        return new Location(LengthUnit.Millimeters, x, y, 0, rotation);
    }

    private static void assertLocation(double x, double y, Location actual) {
        assertEquals(x, actual.getX(), EPSILON);
        assertEquals(y, actual.getY(), EPSILON);
        assertEquals(0, actual.getZ(), EPSILON);
        assertEquals(0, actual.getRotation(), EPSILON);
    }
}
