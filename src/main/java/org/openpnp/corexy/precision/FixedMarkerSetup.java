package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Five permanent, individually named references, including the visual Home datum. */
public final class FixedMarkerSetup {
    public static final class Marker {
        public String id, name;
        public double x, y;

        public Marker() { }

        public Marker(String id, String name, double x, double y) {
            this.id = id;
            this.name = name;
            this.x = x;
            this.y = y;
        }
    }

    public int schema = 1;
    public List<Marker> markers = new ArrayList<>();
    public int homeIndex;
    public double planeZ;
    public double markerDiameter = 1;
    public double target = .05;
    public double maximumOffset = .2;
    public double conditioningTravel = 48;
    public double maximumHomeOffset = 1;
    public double homeSearchRadius = 2;
    public int settleMillis = 300;
    public boolean verifyOnStartup = true;

    public void validate() {
        if (schema != 1 || markers == null || markers.size() != 5 || homeIndex < 0 || homeIndex >= 5) {
            throw new IllegalArgumentException("固定 marker 模式必须设置 5 个点，并指定其中一个作为 Home 基准。");
        }
        for (double value : new double[] {planeZ, markerDiameter, target, maximumOffset,
                conditioningTravel, maximumHomeOffset, homeSearchRadius}) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("固定 marker 设置必须是有限数值。");
            }
        }
        if (markerDiameter < .1 || markerDiameter > 10 || target <= 0 || target > 1
                || maximumOffset <= 0 || maximumOffset > 2 || conditioningTravel < 3
                || conditioningTravel > 128 || maximumHomeOffset <= 0 || maximumHomeOffset > 5
                || homeSearchRadius < maximumHomeOffset || homeSearchRadius > 10
                || settleMillis < 0 || settleMillis > 2000) {
            throw new IllegalArgumentException("固定 marker 设置超出支持范围。");
        }
        Set<String> identities = new HashSet<>();
        for (Marker marker : markers) {
            if (marker == null || marker.id == null || !marker.id.matches("[A-Za-z0-9_-]{1,64}")
                    || !identities.add(marker.id) || marker.name == null || marker.name.trim().isEmpty()
                    || !Double.isFinite(marker.x) || !Double.isFinite(marker.y)) {
                throw new IllegalArgumentException("每个 marker 必须有唯一编号、名称和有效坐标。");
            }
        }
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double largestArea = 0;
        for (int i = 0; i < markers.size(); i++) {
            Marker a = markers.get(i);
            minX = Math.min(minX, a.x);
            maxX = Math.max(maxX, a.x);
            minY = Math.min(minY, a.y);
            maxY = Math.max(maxY, a.y);
            for (int j = i + 1; j < markers.size(); j++) {
                Marker b = markers.get(j);
                if (Math.hypot(a.x - b.x, a.y - b.y) < Math.max(markerDiameter, .1)) {
                    throw new IllegalArgumentException("marker 点重叠或过近，无法分别识别。");
                }
                for (int k = j + 1; k < markers.size(); k++) {
                    Marker c = markers.get(k);
                    largestArea = Math.max(largestArea,
                            Math.abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)));
                }
            }
        }
        if (maxX - minX < 10 || maxY - minY < 10 || largestArea < 1) {
            throw new IllegalArgumentException("5 个 marker 应覆盖工作区域，X/Y 跨度至少 10 mm，且不能共线。");
        }
    }

    public Marker homeMarker() {
        validate();
        return markers.get(homeIndex);
    }

    public Marker marker(String id) {
        for (Marker marker : markers) {
            if (marker.id.equals(id)) {
                return marker;
            }
        }
        throw new IllegalArgumentException("未知固定 marker：" + id);
    }

    public FixedMarkerSetup copy() {
        FixedMarkerSetup copy = new FixedMarkerSetup();
        copy.schema = schema;
        copy.homeIndex = homeIndex;
        copy.planeZ = planeZ;
        copy.markerDiameter = markerDiameter;
        copy.target = target;
        copy.maximumOffset = maximumOffset;
        copy.conditioningTravel = conditioningTravel;
        copy.maximumHomeOffset = maximumHomeOffset;
        copy.homeSearchRadius = homeSearchRadius;
        copy.settleMillis = settleMillis;
        copy.verifyOnStartup = verifyOnStartup;
        for (Marker marker : markers) {
            copy.markers.add(new Marker(marker.id, marker.name, marker.x, marker.y));
        }
        return copy;
    }

    public CalibrationRequest toRequest() {
        validate();
        CalibrationRequest request = new CalibrationRequest();
        request.fixedMarkers = copy();
        request.originX = markers.get(homeIndex).x;
        request.originY = markers.get(homeIndex).y;
        request.planeZ = planeZ;
        request.markerDiameter = markerDiameter;
        request.target = target;
        request.maximumOffset = maximumOffset;
        request.conditioningTravel = conditioningTravel;
        request.settleMillis = settleMillis;
        return request;
    }
}
