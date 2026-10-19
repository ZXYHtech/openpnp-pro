package org.openpnp.stencil;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StencilCameraTest {

    private StencilCamera camera(double yaw, double pitch) {
        return new StencilCamera(new double[] { 0, 0, 0 }, 100, 800, 600, yaw, pitch, 1, 0, 0);
    }

    @Test
    void worldUpStaysUpAndTheFrontIsNotMirrored() {
        StencilCamera c = camera(0, 35);
        double[] origin = c.project(new double[] { 0, 0, 0 });
        assertTrue(c.project(new double[] { 0, 0, 20 })[1] < origin[1]);
        assertTrue(c.project(new double[] { 20, 0, 0 })[0] > origin[0]);
        assertTrue(c.project(new double[] { 0, 20, 0 })[1] < origin[1]);
    }

    @Test
    void orbitDoesNotPumpTheFittedDistanceOrFocalLength() {
        StencilCamera original = camera(0, 45);
        for (int yaw = -360; yaw <= 360; yaw += 15) {
            for (int pitch = -89; pitch <= 90; pitch += 7) {
                StencilCamera c = camera(yaw, pitch);
                assertEquals(original.distance, c.distance, 1e-12);
                assertEquals(original.focal, c.focal, 1e-12);
                assertArrayEquals(new double[] { 400, 300, 1 / c.distance }, c.project(new double[] { 0, 0, 0 }), 1e-12);
            }
        }
    }

    @Test
    void perspectiveMakesNearObjectsLargerWithTheExpectedRatio() {
        StencilCamera c = camera(0, 0);
        double nearWidth = c.project(new double[] { 10, -40, 0 })[0] - c.project(new double[] { -10, -40, 0 })[0];
        double farWidth = c.project(new double[] { 10, 40, 0 })[0] - c.project(new double[] { -10, 40, 0 })[0];
        assertEquals((c.distance + 40) / (c.distance - 40), nearWidth / farWidth, 1e-12);
    }

    @Test
    void topViewPreservesCirclesAcrossWideAndTallWindows() {
        for (int[] size : new int[][] { { 1200, 300 }, { 300, 1200 }, { 600, 600 } }) {
            StencilCamera c = new StencilCamera(new double[] { 0, 0, 0 }, 100, size[0], size[1], 0, 90, 1, 0, 0);
            double[] center = c.project(new double[] { 0, 0, 0 });
            double dx = c.project(new double[] { 30, 0, 0 })[0] - center[0];
            double dy = center[1] - c.project(new double[] { 0, 30, 0 })[1];
            assertEquals(dx, dy, 1e-10);
            assertTrue(dx > 0);
        }
    }

    @Test
    void pixelRaysRecoverTheWorldPlaneAfterOrbitPanAndZoom() {
        double[] target = { 12, -8, 3 };
        for (int yaw = -180; yaw <= 180; yaw += 30) {
            for (int pitch : new int[] { 15, 45, 80, 90 }) {
                for (double zoom : new double[] { .15, 1, 8 }) {
                    StencilCamera c = new StencilCamera(target, 100, 640, 420, yaw, pitch, zoom, 37, -21);
                    for (double[] p : new double[][] { { 0, 0, 5 }, { -40, 15, 10 }, { 30, -20, 2 } }) {
                        double[] screen = c.project(p);
                        assertArrayEquals(p, c.pickTop(screen[0], screen[1], p[2]), 1e-9);
                    }
                }
            }
        }
    }

    @Test
    void edgeOnAndUndersideCannotPlaceTextOnTheTop() {
        assertNull(camera(0, 0).pickTop(400, 300, 0));
        assertNull(camera(0, -45).pickTop(400, 300, 0));
        StencilCamera c = camera(0, 90);
        assertNull(c.project(new double[] { 0, 0, c.distance + 1 }));
    }

    @Test
    void inverseDepthInterpolatesAPlaneCorrectlyInScreenSpace() {
        StencilCamera c = camera(30, 40);
        double[] a = c.project(new double[] { -40, -30, 10 });
        double[] b = c.project(new double[] { 50, -20, 10 });
        double[] d = c.project(new double[] { 0, 50, 10 });
        double x = (a[0] + b[0] + d[0]) / 3, y = (a[1] + b[1] + d[1]) / 3;
        double[] point = c.pickTop(x, y, 10);
        assertEquals(c.project(point)[2], (a[2] + b[2] + d[2]) / 3, 1e-12);
    }
}
