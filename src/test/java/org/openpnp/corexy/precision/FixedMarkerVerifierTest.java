package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class FixedMarkerVerifierTest {
    @Test
    void repeatedSwitchDriftIsRemovedBeforeCheckingAllFiveReferences() throws Exception {
        PrecisionProfile profile = profile();
        PhysicalReferences plant = new PhysicalReferences(profile);
        for (int home = 0; home < 8; home++) {
            plant.driftX = .3 + home * .027;
            plant.driftY = -.2 + home * .019;
            StartupVerificationReport report = new StartupVerificationReport();
            FixedMarkerVerifier verifier = new FixedMarkerVerifier();
            verifier.alignHome(plant, profile, report, (p, m) -> {}, () -> false);
            verifier.verify(plant, profile, report, (p, m) -> {}, () -> false);
            assertTrue(report.passed);
            assertTrue(report.beforeHomeResidual > .2);
            assertTrue(report.afterHomeResidual <= .002);
            assertEquals(18, report.samples.size());
            assertEquals(-report.rawHomeX, report.homeCorrectionX, .001);
            assertTrue(report.metrics.p95 < .002);
            assertTrue(plant.datumWrites > home);
        }
    }

    @Test
    void aChangedNonHomeMarkerFailsEvenWhenHomeDatumLooksPerfect() throws Exception {
        PrecisionProfile profile = profile();
        PhysicalReferences plant = new PhysicalReferences(profile);
        plant.movedMarker = true;
        StartupVerificationReport report = new StartupVerificationReport();
        FixedMarkerVerifier verifier = new FixedMarkerVerifier();
        verifier.alignHome(plant, profile, report, (p, m) -> {}, () -> false);
        assertThrows(IllegalStateException.class, () -> verifier.verify(plant, profile, report, (p, m) -> {}, () -> false));
        assertFalse(report.passed);
        assertTrue(report.metrics.p95 > profile.corrected.p95);
        assertEquals(18, report.samples.size(), "Failed checks retain every measured observation");
        assertTrue(report.afterHomeResidual < .002);
    }

    @Test
    void excessiveHomeDriftDoesNotWriteCoordinateOffsets() {
        PrecisionProfile profile = profile();
        PhysicalReferences plant = new PhysicalReferences(profile);
        plant.driftX = 1.3;
        StartupVerificationReport report = new StartupVerificationReport();
        assertThrows(IllegalStateException.class, () -> new FixedMarkerVerifier().alignHome(plant, profile, report,
                (p, m) -> {}, () -> false));
        assertEquals(0, plant.datumWrites);
        assertEquals(5, report.homeSamples.size());
        assertTrue(report.beforeHomeResidual > 1);
    }

    @Test
    void highNoiseAndNonConvergingDatumAreRejected() {
        PrecisionProfile profile = profile();
        PhysicalReferences noisy = new PhysicalReferences(profile);
        noisy.noisy = true;
        StartupVerificationReport report = new StartupVerificationReport();
        assertThrows(IllegalStateException.class, () -> new FixedMarkerVerifier().alignHome(noisy, profile, report,
                (p, m) -> {}, () -> false));
        assertEquals(0, noisy.datumWrites);
        PhysicalReferences broken = new PhysicalReferences(profile);
        broken.driftX = .1;
        broken.ignoreDatumWrites = true;
        assertThrows(IllegalStateException.class, () -> new FixedMarkerVerifier().alignHome(broken, profile,
                new StartupVerificationReport(), (p, m) -> {}, () -> false));
        assertEquals(3, broken.datumWrites, "Correction is bounded, never an infinite feedback loop");
    }

    @Test
    void cancellationPreventsMotionAndHomeCheckNeverRecursivelyHomes() {
        PrecisionProfile profile = profile();
        PhysicalReferences plant = new PhysicalReferences(profile);
        StartupVerificationReport report = new StartupVerificationReport();
        assertThrows(CancellationException.class, () -> new FixedMarkerVerifier().alignHome(plant, profile,
                report, (p, m) -> {}, () -> true));
        assertEquals(0, plant.frames);
        for (CalibrationPlan.Path path : FixedMarkerVerifier.verificationPaths(profile.fixedMarkers, 100)) {
            assertFalse(path.homeStart);
            FixedMarkerSetup.Marker marker = profile.fixedMarkers.marker(path.markerId);
            assertEquals(marker.x, path.x);
            assertEquals(marker.y, path.y);
            if (marker == profile.fixedMarkers.homeMarker()) {
                assertTrue(path.direct);
                assertEquals(1, path.points(48).length);
            }
        }
    }

    static PrecisionProfile profile() {
        PrecisionProfile profile = new PrecisionProfile();
        FixedMarkerSetup setup = new FixedMarkerSetup();
        setup.markers.add(new FixedMarkerSetup.Marker("home", "Home", 0, 0));
        setup.markers.add(new FixedMarkerSetup.Marker("p1", "左下", 80, 80));
        setup.markers.add(new FixedMarkerSetup.Marker("p2", "右下", 220, 80));
        setup.markers.add(new FixedMarkerSetup.Marker("p3", "左上", 80, 220));
        setup.markers.add(new FixedMarkerSetup.Marker("p4", "右上", 220, 220));
        profile.fixedMarkers = setup;
        profile.target = .05;
        profile.noise = .0003;
        java.util.List<PrecisionSample> samples = new java.util.ArrayList<>();
        for (int n = 0; n < 20; n++) { samples.add(sample("baseline-" + n, 100, 100, .004, -.001)); }
        profile.corrected = PrecisionMetrics.evaluate(samples, null);
        return profile;
    }

    private static PrecisionSample sample(String id, double x, double y, double dx, double dy) {
        return new PrecisionSample(id, "camera", x, y, 0, 0, .5, 1000, Double.NaN, 0,
                0, 0, 0, 0, 100, 0, 0, dx, dy);
    }

    /** Independent camera observations with switch drift and one displaced physical reference. */
    private static final class PhysicalReferences implements FixedMarkerVerifier.Backend {
        final Random random = new Random(8877);
        final PrecisionProfile profile;
        double driftX, driftY;
        boolean movedMarker, noisy, ignoreDatumWrites;
        int datumWrites, frames;
        PhysicalReferences(PrecisionProfile profile) { this.profile = profile; }
        @Override public PrecisionSample observe(CalibrationPlan.Path path) {
            frames++;
            double noise = noisy ? ((frames % 2 == 0) ? .05 : -.05) : random.nextGaussian() * .00015;
            double moved = movedMarker && "p3".equals(path.markerId) ? .025 : 0;
            return sample(path.id, path.x, path.y, driftX + moved + noise, driftY + noise / 2);
        }
        @Override public void subtractDatum(double x, double y) {
            datumWrites++;
            if (!ignoreDatumWrites) {
                driftX -= x;
                driftY -= y;
            }
        }
    }
}
