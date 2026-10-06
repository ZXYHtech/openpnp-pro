package org.openpnp.machine.reference.feeder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/** Selects an EIA-481 sprocket hole from a detected, equally spaced straight hole row. */
public final class StripFeederCalibrationVision {
    private static final double MAX_LINE_ERROR_MM = 0.55;
    private static final double MAX_PITCH_ERROR_MM = 0.55;
    public static final double MAX_MICRO_ADJUSTMENT_MM = 1.35;

    public static final class Selection {
        public final Location location;
        public final int supportCount;
        public final double meanPitchErrorMm;

        private Selection(Location location, int supportCount, double meanPitchErrorMm) {
            this.location = location;
            this.supportCount = supportCount;
            this.meanPitchErrorMm = meanPitchErrorMm;
        }
    }

    private static final class Row {
        private final List<Location> members;
        private final double meanPitchErrorMm;
        private final double score;

        private Row(List<Location> members, double meanPitchErrorMm, double score) {
            this.members = members;
            this.meanPitchErrorMm = meanPitchErrorMm;
            this.score = score;
        }
    }

    private StripFeederCalibrationVision() {
    }

    public static Selection select(List<Location> candidates, Location expected,
            double holePitchMm) throws Exception {
        if (candidates == null || candidates.isEmpty() || expected == null) {
            throw new Exception("未检测到可用于编带纠偏的定位孔。");
        }
        if (!Double.isFinite(holePitchMm) || holePitchMm <= 0) {
            throw new Exception("编带定位孔节距无效。");
        }
        Location expectedMm = expected.convertToUnits(LengthUnit.Millimeters);
        List<Location> points = new ArrayList<>();
        for (Location candidate : candidates) {
            if (candidate != null) {
                points.add(candidate.convertToUnits(LengthUnit.Millimeters));
            }
        }
        if (points.size() == 1) {
            Location only = points.get(0);
            double distance = only.getLinearDistanceTo(expectedMm);
            if (distance > MAX_MICRO_ADJUSTMENT_MM) {
                throw new Exception(String.format(Locale.ROOT,
                        "只检测到一个定位孔，且距预计位置 %.2f mm，无法确认孔列。", distance));
            }
            return new Selection(candidates.get(0), 1, 0);
        }

        Row best = null;
        for (int firstIndex = 0; firstIndex < points.size(); firstIndex++) {
            for (int secondIndex = firstIndex + 1; secondIndex < points.size(); secondIndex++) {
                Location first = points.get(firstIndex);
                Location second = points.get(secondIndex);
                double dx = second.getX() - first.getX();
                double dy = second.getY() - first.getY();
                double distance = Math.hypot(dx, dy);
                long intervals = Math.round(distance / holePitchMm);
                if (intervals < 1
                        || Math.abs(distance - intervals * holePitchMm) > MAX_PITCH_ERROR_MM) {
                    continue;
                }
                double ux = dx / distance;
                double uy = dy / distance;
                List<Location> members = new ArrayList<>();
                double totalPitchError = 0;
                double minProjection = 0;
                double maxProjection = 0;
                for (Location point : points) {
                    double pointX = point.getX() - first.getX();
                    double pointY = point.getY() - first.getY();
                    double projection = pointX * ux + pointY * uy;
                    double lineError = Math.abs(pointX * uy - pointY * ux);
                    long pointIntervals = Math.round(projection / holePitchMm);
                    double pitchError = Math.abs(projection - pointIntervals * holePitchMm);
                    if (lineError <= MAX_LINE_ERROR_MM && pitchError <= MAX_PITCH_ERROR_MM) {
                        members.add(point);
                        totalPitchError += pitchError;
                        minProjection = Math.min(minProjection, projection);
                        maxProjection = Math.max(maxProjection, projection);
                    }
                }
                if (members.size() < 2) {
                    continue;
                }
                double meanError = totalPitchError / members.size();
                double spanIntervals = (maxProjection - minProjection) / holePitchMm;
                double nearestExpected = members.stream()
                        .mapToDouble(point -> point.getLinearDistanceTo(expectedMm))
                        .min().orElse(Double.POSITIVE_INFINITY);
                double score = members.size() * 100.0 + spanIntervals * 5.0
                        - meanError * 20.0 - nearestExpected;
                if (best == null || score > best.score) {
                    best = new Row(members, meanError, score);
                }
            }
        }
        if (best == null) {
            throw new Exception(String.format(Locale.ROOT,
                    "检测到 %d 个圆形候选，但没有形成孔距 %.2f mm 的直线孔列。",
                    points.size(), holePitchMm));
        }
        if (points.size() >= 3 && best.members.size() < 3) {
            throw new Exception(String.format(Locale.ROOT,
                    "检测到 %d 个圆形候选，但只有两个符合孔距；请改善灯光或把相机移到能看到至少三个定位孔的位置。",
                    points.size()));
        }
        Location selectedMm = best.members.stream()
                .min((first, second) -> Double.compare(first.getLinearDistanceTo(expectedMm),
                        second.getLinearDistanceTo(expectedMm)))
                .orElseThrow(() -> new Exception("定位孔列为空。"));
        double selectedShift = selectedMm.getLinearDistanceTo(expectedMm);
        if (selectedShift > MAX_MICRO_ADJUSTMENT_MM) {
            throw new Exception(String.format(Locale.ROOT,
                    "定位孔偏移 %.2f mm，超过手动条带飞达微调上限 %.2f mm。",
                    selectedShift, MAX_MICRO_ADJUSTMENT_MM));
        }
        return new Selection(selectedMm.convertToUnits(expected.getUnits()), best.members.size(),
                best.meanPitchErrorMm);
    }
}
