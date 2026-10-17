package org.openpnp.corexy.precision;

/** Preserve endpoints and A/B reversals while fitting each measured stroke inside travel limits. */
public final class FixedMarkerTravel {
    private FixedMarkerTravel() { }

    public static CalibrationPlan.Path fit(CalibrationPlan.Path path, double conditioning,
            double minX, double maxX, double minY, double maxY) {
        if (path.homeStart || path.direct) {
            return path;
        }
        if (!Double.isFinite(minX) || !Double.isFinite(maxX) || !Double.isFinite(minY)
                || !Double.isFinite(maxY) || minX >= maxX || minY >= maxY) {
            throw new IllegalStateException("固定 marker 需要有效的软限位安全范围。");
        }
        if (path.x < minX || path.x > maxX || path.y < minY || path.y > maxY) {
            throw new IllegalStateException("固定 marker 缺少补偿安全余量：" + path.markerId);
        }
        double dx = (path.signA * path.a + path.signB * path.b) / 2;
        double dy = (path.signA * path.a - path.signB * path.b) / 2;
        double scale = Math.min(1, Math.min(available(path.x, -dx, minX, maxX),
                available(path.y, -dy, minY, maxY)));
        // A sub-pixel/vanishing stroke cannot establish a meaningful direction state.
        double shortest = Math.min(path.a, path.b);
        if (scale <= 0 || shortest * scale < Math.min(.5, shortest)) {
            throw new IllegalStateException("固定 marker 换向采样空间不足：" + path.markerId
                    + "；请将此点移向工作区内部。");
        }
        double sx = path.x - dx * scale, sy = path.y - dy * scale;
        double cx = (path.signA + path.signB) / 2.0;
        double cy = (path.signA - path.signB) / 2.0;
        double travel = Math.min(conditioning, Math.min(available(sx, cx, minX, maxX),
                available(sy, cy, minY, maxY)));
        if (travel < 3) {
            throw new IllegalStateException("固定 marker 换向预行程不足 3 mm：" + path.markerId
                    + "；请将此点移向工作区内部。");
        }
        if (scale == 1 && travel == conditioning) {
            return path;
        }
        CalibrationPlan.Path fitted = new CalibrationPlan.Path(path.id, path.markerId,
                path.x, path.y, path.a * scale, path.b * scale, path.speed,
                path.accelerationFactor, path.signA, path.signB, path.pieces, path.settleMillis);
        fitted.conditioningTravel = travel;
        return fitted;
    }

    private static double available(double point, double direction, double low, double high) {
        return direction > 0 ? (high - point) / direction
                : direction < 0 ? (low - point) / direction : Double.POSITIVE_INFINITY;
    }
}