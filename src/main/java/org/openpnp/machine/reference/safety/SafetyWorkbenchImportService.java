/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;
import org.openpnp.spi.base.AbstractHead;

/**
 * Imports existing machine configuration into the Pro safety workbench. Imported
 * point objects have stable source keys, so running the import again updates them
 * instead of creating duplicates.
 */
public final class SafetyWorkbenchImportService {
    public enum Category {
        WORK_AREA,
        HOME_AND_PARK,
        NOZZLE_CHANGER,
        DISCARD,
        FEEDER_PICK_POINTS
    }

    public static final class Summary {
        private int created;
        private int updated;
        private final List<String> details = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        public int getCreated() {
            return created;
        }

        public int getUpdated() {
            return updated;
        }

        public List<String> getDetails() {
            return details;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public String toDisplayText() {
            StringBuilder text = new StringBuilder("从 OpenPnP 配置导入完成：新增 ")
                    .append(created).append("，更新 ").append(updated).append("。\n");
            for (String detail : details) {
                text.append("• ").append(detail).append('\n');
            }
            for (String warning : warnings) {
                text.append("⚠ ").append(warning).append('\n');
            }
            return text.toString().trim();
        }
    }

    private SafetyWorkbenchImportService() {
    }

    public static Summary importConfiguration(SafetyWorkbenchModel model, Machine machine,
            Nozzle selectedNozzle, Set<Category> categories) {
        Summary summary = new Summary();
        if (categories.contains(Category.WORK_AREA)) {
            importWorkArea(model, machine, selectedNozzle, summary);
        }
        if (categories.contains(Category.HOME_AND_PARK)) {
            importHomeAndPark(model, machine, summary);
        }
        if (categories.contains(Category.NOZZLE_CHANGER)) {
            importNozzleChanger(model, machine, summary);
        }
        if (categories.contains(Category.DISCARD)) {
            Location discard = machine.getDiscardLocation();
            if (isUsable(discard)) {
                upsert(model, ObjectType.DISCARD_POINT, "machine:discard",
                        "机器抛料点", discard, summary);
                summary.details.add("已同步机器抛料点");
            }
            else {
                summary.warnings.add("机器没有可用的抛料点坐标");
            }
        }
        if (categories.contains(Category.FEEDER_PICK_POINTS)) {
            importFeederPickPoints(model, machine, summary);
        }
        return summary;
    }

    public static EnumSet<Category> allCategories() {
        return EnumSet.allOf(Category.class);
    }

    private static void importWorkArea(SafetyWorkbenchModel model, Machine machine,
            Nozzle selectedNozzle, Summary summary) {
        Range x = new Range();
        Range y = new Range();
        Range z = new Range();
        for (Axis axis : machine.getAxes()) {
            if (!(axis instanceof ReferenceControllerAxis)) {
                continue;
            }
            ReferenceControllerAxis controller = (ReferenceControllerAxis) axis;
            Range range = axis.getType() == Axis.Type.X ? x
                    : axis.getType() == Axis.Type.Y ? y
                            : axis.getType() == Axis.Type.Z ? z : null;
            if (range == null) {
                continue;
            }
            if (controller.isSoftLimitLowEnabled()) {
                range.include(controller.getSoftLimitLow());
            }
            if (controller.isSoftLimitHighEnabled()) {
                range.include(controller.getSoftLimitHigh());
            }
        }
        int importedAxes = 0;
        if (x.isComplete()) {
            model.setMinX(x.minimum);
            model.setMaxX(x.maximum);
            importedAxes++;
        }
        if (y.isComplete()) {
            model.setMinY(y.minimum);
            model.setMaxY(y.maximum);
            importedAxes++;
        }
        if (z.isComplete()) {
            model.setMinZ(z.minimum);
            model.setMaxZ(z.maximum);
            importedAxes++;
        }
        if (importedAxes > 0) {
            summary.details.add("已从轴软限位同步 " + importedAxes + " 个工作范围");
        }
        else {
            summary.warnings.add("没有找到成对启用的 XYZ 轴软限位，工作范围保持不变");
        }

        Nozzle nozzle = selectedNozzle == null ? firstNozzle(machine) : selectedNozzle;
        if (nozzle != null) {
            Length[] safeZone = nozzle.getSafeZZone();
            if (safeZone != null && safeZone.length >= 2
                    && safeZone[0] != null && safeZone[1] != null) {
                double low = millimeters(safeZone[0]);
                double high = millimeters(safeZone[1]);
                model.setImportedSafeZ(low, high);
                summary.details.add(String.format(java.util.Locale.ROOT,
                        "已同步 %s 的安全 Z 区 %.3f ～ %.3f mm",
                        nozzle.getName(), Math.min(low, high), Math.max(low, high)));
            }
            else {
                summary.warnings.add("所选吸嘴没有同时定义安全 Z 上下界");
            }
        }
    }

    private static void importHomeAndPark(SafetyWorkbenchModel model, Machine machine,
            Summary summary) {
        Location axesHome = axesHome(machine);
        if (isUsable(axesHome)) {
            upsert(model, ObjectType.HOME_POINT, "machine:axes-home",
                    "机器 Home（轴定义）", axesHome, summary);
        }
        int points = axesHome == null ? 0 : 1;
        for (Head head : machine.getHeads()) {
            if (head instanceof AbstractHead) {
                Location home = ((AbstractHead) head).getHomingFiducialLocation();
                if (isUsable(home)) {
                    upsert(model, ObjectType.HOME_POINT, "head:" + head.getId() + ":home",
                            head.getName() + " · Home 基准", home, summary);
                    points++;
                }
            }
            Location park = head.getParkLocation();
            if (isUsable(park)) {
                upsert(model, ObjectType.PARK_POINT, "head:" + head.getId() + ":park",
                        head.getName() + " · 停靠点", park, summary);
                points++;
            }
        }
        if (points > 0) {
            summary.details.add("已同步 Home/停靠参考点 " + points + " 个");
        }
        else {
            summary.warnings.add("没有找到已定义的 Home 或停靠点");
        }
    }

    private static void importNozzleChanger(SafetyWorkbenchModel model, Machine machine,
            Summary summary) {
        int points = 0;
        for (NozzleTip nozzleTip : machine.getNozzleTips()) {
            if (!(nozzleTip instanceof ReferenceNozzleTip)) {
                continue;
            }
            ReferenceNozzleTip tip = (ReferenceNozzleTip) nozzleTip;
            Location[] locations = {tip.getChangerStartLocation(), tip.getChangerMidLocation(),
                    tip.getChangerMidLocation2(), tip.getChangerEndLocation()};
            String[] labels = {"起点", "中点 1", "中点 2", "终点"};
            boolean configured = false;
            for (Location location : locations) {
                configured |= isUsable(location) && !isOrigin(location);
            }
            if (!configured) {
                continue;
            }
            for (int index = 0; index < locations.length; index++) {
                if (!isUsable(locations[index])) {
                    continue;
                }
                upsert(model, ObjectType.NOZZLE_CHANGER_POINT,
                        "nozzle-tip:" + nozzleTip.getId() + ":changer:" + index,
                        nozzleTip.getName() + " · 换嘴" + labels[index],
                        locations[index], summary);
                points++;
            }
        }
        if (points > 0) {
            summary.details.add("已同步自动换吸嘴路径点 " + points + " 个");
        }
        else {
            summary.warnings.add("没有找到已配置的自动换吸嘴路径");
        }
    }

    private static void importFeederPickPoints(SafetyWorkbenchModel model, Machine machine,
            Summary summary) {
        int points = 0;
        for (Feeder feeder : machine.getFeeders()) {
            try {
                Location location = feeder.getPickLocation();
                if (!isUsable(location)) {
                    continue;
                }
                upsert(model, ObjectType.FEEDER_PICK_POINT,
                        "feeder:" + feeder.getId() + ":pick",
                        feeder.getName() + " · 取料点"
                                + (feeder.isEnabled() ? "" : "（未启用）"),
                        location, summary);
                points++;
            }
            catch (Exception error) {
                summary.warnings.add("飞达“" + feeder.getName() + "”取料点读取失败："
                        + error.getMessage());
            }
        }
        if (points > 0) {
            summary.details.add("已同步飞达取料点 " + points + " 个");
        }
        else {
            summary.warnings.add("没有读取到可用的飞达取料点");
        }
    }

    private static void upsert(SafetyWorkbenchModel model, ObjectType type, String sourceKey,
            String name, Location location, Summary summary) {
        SafetyObject object = null;
        for (SafetyObject candidate : model.getObjects()) {
            if (sourceKey.equals(candidate.sourceKey)) {
                object = candidate;
                break;
            }
        }
        if (object == null) {
            object = model.createObject(type);
            model.getObjects().add(object);
            summary.created++;
        }
        else {
            summary.updated++;
        }
        Location millimeters = location.convertToUnits(LengthUnit.Millimeters);
        object.type = type;
        object.name = name;
        object.sourceKey = sourceKey;
        object.x = millimeters.getX();
        object.y = millimeters.getY();
        object.z = Double.isFinite(millimeters.getZ()) ? millimeters.getZ() : model.getMinZ();
        object.obstacle = false;
        object.locked = true;
        object.enabled = true;
        model.touch();
    }

    private static Location axesHome(Machine machine) {
        double x = Double.NaN;
        double y = Double.NaN;
        double z = Double.NaN;
        for (Axis axis : machine.getAxes()) {
            if (!(axis instanceof ReferenceControllerAxis)) {
                continue;
            }
            double value = millimeters(((ReferenceControllerAxis) axis).getHomeCoordinate());
            if (axis.getType() == Axis.Type.X && !Double.isFinite(x)) {
                x = value;
            }
            else if (axis.getType() == Axis.Type.Y && !Double.isFinite(y)) {
                y = value;
            }
            else if (axis.getType() == Axis.Type.Z && !Double.isFinite(z)) {
                z = value;
            }
        }
        return Double.isFinite(x) && Double.isFinite(y)
                ? new Location(LengthUnit.Millimeters, x, y,
                        Double.isFinite(z) ? z : 0, 0) : null;
    }

    private static Nozzle firstNozzle(Machine machine) {
        for (Head head : machine.getHeads()) {
            if (!head.getNozzles().isEmpty()) {
                return head.getNozzles().get(0);
            }
        }
        return null;
    }

    private static boolean isUsable(Location location) {
        if (location == null) {
            return false;
        }
        Location millimeters = location.convertToUnits(LengthUnit.Millimeters);
        return Double.isFinite(millimeters.getX()) && Double.isFinite(millimeters.getY());
    }

    private static boolean isOrigin(Location location) {
        Location millimeters = location.convertToUnits(LengthUnit.Millimeters);
        return Math.abs(millimeters.getX()) < 0.000001
                && Math.abs(millimeters.getY()) < 0.000001
                && Math.abs(millimeters.getZ()) < 0.000001;
    }

    private static double millimeters(Length length) {
        return length.convertToUnits(LengthUnit.Millimeters).getValue();
    }

    private static final class Range {
        private double minimum = Double.POSITIVE_INFINITY;
        private double maximum = Double.NEGATIVE_INFINITY;

        void include(Length value) {
            double millimeters = SafetyWorkbenchImportService.millimeters(value);
            minimum = Math.min(minimum, millimeters);
            maximum = Math.max(maximum, millimeters);
        }

        boolean isComplete() {
            return Double.isFinite(minimum) && Double.isFinite(maximum) && minimum < maximum;
        }
    }
}
