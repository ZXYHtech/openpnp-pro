package org.openpnp.stencil;

/**
 * Upright, rigid turntable camera. A fixed bounding sphere keeps orbiting from
 * changing the fitted distance. Screen X and Y use the same perspective scale.
 */
final class StencilCamera {

    private static final double HALF_FOV = Math.toRadians(38) / 2;

    final double distance, focal;

    private final double[] target, right, up, viewer;

    private final double cx, cy;

    StencilCamera(double[] target, double radius, int width, int height, double yaw, double pitch, double zoom, double panX, double panY) {
        this.target = target.clone();
        double a = Math.toRadians(yaw), b = Math.toRadians(pitch);
        double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b);
        right = new double[] { ca, -sa, 0 };
        up = new double[] { sa * sb, ca * sb, cb };
        viewer = new double[] { -sa * cb, -ca * cb, sb };
        double aspect = Math.max(1, width - 48) / (double) Math.max(1, height - 48);
        double halfAngle = Math.min(HALF_FOV, Math.atan(Math.tan(HALF_FOV) * aspect));
        distance = Math.max(1, radius) * 1.08 / Math.sin(halfAngle);
        // Optical zoom does not move the eye into (or through) the model.
        focal = Math.max(1, height - 48) * zoom / (2 * Math.tan(HALF_FOV));
        cx = width / 2.0 + panX;
        cy = height / 2.0 + panY;
    }

    /**
     * Screen X, screen Y and reciprocal eye distance, or null behind the eye.
     */
    double[] project(double[] point) {
        double x = point[0] - target[0], y = point[1] - target[1], z = point[2] - target[2];
        double depth = distance - dot(viewer, x, y, z);
        if (depth <= 1e-6) {
            return null;
        }
        return new double[] { cx + focal * dot(right, x, y, z) / depth, cy - focal * dot(up, x, y, z) / depth, 1 / depth };
    }

    /**
     * Intersect a pixel ray with the visible upper side of a horizontal plane.
     */
    double[] pickTop(double screenX, double screenY, double planeZ) {
        double rx = (screenX - cx) / focal, uy = (cy - screenY) / focal;
        double dx = right[0] * rx + up[0] * uy - viewer[0];
        double dy = right[1] * rx + up[1] * uy - viewer[1];
        double dz = right[2] * rx + up[2] * uy - viewer[2];
        double eyeZ = target[2] + viewer[2] * distance;
        if (dz >= -1e-8 || eyeZ <= planeZ) {
            return null;
        }
        double t = (planeZ - eyeZ) / dz;
        return new double[] { target[0] + viewer[0] * distance + dx * t, target[1] + viewer[1] * distance + dy * t, planeZ };
    }

    private static double dot(double[] v, double x, double y, double z) {
        return v[0] * x + v[1] * y + v[2] * z;
    }
}
