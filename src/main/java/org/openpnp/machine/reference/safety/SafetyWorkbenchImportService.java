/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.ObjectType;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;
import org.openpnp.spi.base.AbstractHead;

/**
 * Imports existing machine configuration into the Pro safety workbench. Imported
 * reference points and physical bodies have stable source keys, so running the
 * import again updates them instead of creating duplicates.
 */
public final class SafetyWorkbenchImportService {
    public enum Category {
        WORK_AREA,
        HOME_AND_PARK,
        NOZZLE_CHANGER,
        DISCARD,
        FEEDER_PICK_POINTS,
        BOTTOM_CAMERAS
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

    public static final class FeederType {
        private final String key;
        private final String label;
        private int count;
        private int enabledCount;

        private FeederType(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String getKey() {
            return key;
        }

        public String getLabel() {
            return label;
        }

        public int getCount() {
            return count;
        }

        public int getEnabledCount() {
            return enabledCount;
        }
    }

    private SafetyWorkbenchImportService() {
    }

    public static Summary importConfiguration(SafetyWorkbenchModel model, Machine machine,
            Nozzle selectedNozzle, Set<Category> categories) {
        return importConfiguration(model, machine, selectedNozzle, categories, null);
    }

    public static Summary importConfiguration(SafetyWorkbenchModel model, Machine machine,
            Nozzle selectedNozzle, Set<Category> categories, Set<String> feederTypeKeys) {
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
            importFeederPickPoints(model, machine, feederTypeKeys, summary);
        }
        if (categories.contains(Category.BOTTOM_CAMERAS)) {
            importBottomCameras(model, machine, summary);
        }
        if (model.getObjects().stream().anyMatch(o -> o.sourceKey.startsWith("feeder-bank:"))) {
            aggregateMeasuredFeederBanks(model);
        }
        return summary;
    }

    /** Union measured feeder bodies without reducing any occupied volume. */
    public static void aggregateMeasuredFeederBanks(SafetyWorkbenchModel model) {
        if (model.getMeasuredFeederTopZ() == null || model.getMeasuredFrontFeederMaxY() == null
                || model.getMeasuredRearFeederMinY() == null) {
            throw new IllegalStateException("需先测量前后排飞达边界和最高点");
        }
        for (boolean rear : new boolean[] { false, true }) {
            List<SafetyObject> members = new ArrayList<>();
            for (SafetyObject object : model.getObjects()) {
                if (object.type == ObjectType.FEEDER && object.enabled && object.obstacle
                        && (object.sourceKey.startsWith("feeder:") || object.sourceKey.startsWith("feeder-bank:"))
                        && object.rearFacing == rear
                        && Math.abs(object.z + object.height - model.getMeasuredFeederTopZ()) < .001
                        && (rear ? object.y >= model.getMeasuredRearFeederMinY() - .001
                                : object.y + object.depth <= model.getMeasuredFrontFeederMaxY() + .001)) {
                    members.add(object);
                }
            }
            if (members.isEmpty()) { continue; }
            SafetyObject bank = members.get(0).copy();
            bank.x = members.get(0).x;
            bank.y = members.get(0).y;
            bank.locked = true;
            double maxX = bank.x + bank.width, maxY = bank.y + bank.depth, maxZ = bank.z + bank.height;
            for (SafetyObject member : members) {
                maxX = Math.max(maxX, member.x + member.width);
                maxY = Math.max(maxY, member.y + member.depth);
                maxZ = Math.max(maxZ, member.z + member.height);
                bank.x = Math.min(bank.x, member.x);
                bank.y = Math.min(bank.y, member.y);
                bank.z = Math.min(bank.z, member.z);
                bank.clearance = Math.max(bank.clearance, member.clearance);
            }
            bank.width = maxX - bank.x;
            bank.depth = maxY - bank.y;
            bank.height = maxZ - bank.z;
            bank.name = rear ? "后排电动飞达整体包络" : "前排电动飞达整体包络";
            bank.sourceKey = rear ? "feeder-bank:rear" : "feeder-bank:front";
            model.getObjects().removeAll(members);
            model.addObject(bank);
        }
    }

    public static EnumSet<Category> allCategories() {
        return EnumSet.allOf(Category.class);
    }

    public static List<FeederType> feederTypes(Machine machine) {
        Map<String, FeederType> grouped = new LinkedHashMap<>();
        for (Feeder feeder : machine.getFeeders()) {
            String key = feederTypeKey(feeder);
            FeederType type = grouped.computeIfAbsent(key,
                    ignored -> new FeederType(key, feederTypeLabel(feeder)));
            type.count++;
            if (feeder.isEnabled()) {
                type.enabledCount++;
            }
        }
        return new ArrayList<>(grouped.values());
    }

    public static String feederTypeKey(Feeder feeder) {
        return feederTypeClass(feeder).getName();
    }

    private static String feederTypeLabel(Feeder feeder) {
        Class<?> type = feederTypeClass(feeder);
        String simpleName = type.getSimpleName();
        String name;
        switch (simpleName) {
            case "ReferenceStripFeeder":
                name = "条带飞达";
                break;
            case "ReferenceTrayFeeder":
            case "ReferenceRotatedTrayFeeder":
                name = "托盘飞达";
                break;
            case "ReferenceTubeFeeder":
                name = "管式飞达";
                break;
            case "ReferenceAutoFeeder":
            case "ReferenceSlotAutoFeeder":
                name = "自动飞达";
                break;
            case "ReferencePushPullFeeder":
            case "ReferenceDragFeeder":
            case "ReferenceLeverFeeder":
                name = "机械送料飞达";
                break;
            case "ReferenceLoosePartFeeder":
            case "AdvancedLoosePartFeeder":
            case "ReferenceHeapFeeder":
                name = "散料飞达";
                break;
            case "BlindsFeeder":
                name = "百叶窗飞达";
                break;
            case "PhotonFeeder":
                name = "Photon 智能飞达";
                break;
            case "Neoden4Feeder":
                name = "NeoDen 4 飞达";
                break;
            case "RapidFeeder":
                name = "Rapid 飞达";
                break;
            case "SchultzFeeder":
            case "SlotSchultzFeeder":
                name = "Schultz 飞达";
                break;
            default:
                return simpleName.isEmpty() ? type.getName() : simpleName;
        }
        return name + "（" + simpleName + "）";
    }

    private static Class<?> feederTypeClass(Feeder feeder) {
        Class<?> type = feeder.getClass();
        while (type.getSuperclass() != null
                && Feeder.class.isAssignableFrom(type.getSuperclass())
                && (type.isSynthetic() || type.getName().contains("MockitoMock"))) {
            type = type.getSuperclass();
        }
        return type;
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
        if (x.isComplete() && y.isComplete()) {
            model.setXyBoundsAreTravelLimits(true);
        }
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
                    "轴 Home 坐标（控制器定义）", axesHome, summary);
        }
        int points = axesHome == null ? 0 : 1;
        for (Head head : machine.getHeads()) {
            if (head instanceof AbstractHead) {
                Location home = ((AbstractHead) head).getHomingFiducialLocation();
                if (isUsable(home)) {
                    upsert(model, ObjectType.HOME_POINT, "head:" + head.getId() + ":home",
                            head.getName() + " · 视觉 Home 基准", home, summary);
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
        int before = model.getObjects().size();
        model.getObjects().removeIf(object -> object != null
                && object.type == ObjectType.NOZZLE_CHANGER_POINT
                && object.sourceKey != null && object.sourceKey.contains(":changer:"));
        int removed = before - model.getObjects().size();
        if (removed > 0) {
            summary.updated += removed;
            model.touch();
        }

        int docks = 0;
        for (NozzleTip nozzleTip : machine.getNozzleTips()) {
            if (!(nozzleTip instanceof ReferenceNozzleTip)) {
                continue;
            }
            ReferenceNozzleTip tip = (ReferenceNozzleTip) nozzleTip;
            Location[] locations = {tip.getChangerStartLocation(), tip.getChangerMidLocation(),
                    tip.getChangerMidLocation2(), tip.getChangerEndLocation()};
            Location docking = lastConfiguredLocation(locations);
            if (docking == null) {
                continue;
            }
            Location approach = firstDistinctLocation(locations, docking);
            upsertNozzleChanger(model, nozzleTip, docking, approach, summary);
            docks++;
        }
        if (docks > 0) {
            summary.details.add("已同步带 U 形入口的自动换嘴实体坞 " + docks + " 个");
            if (removed > 0) {
                summary.details.add("已移除旧版自动换嘴路径点 " + removed + " 个");
            }
        }
        else {
            summary.warnings.add("没有找到已配置的自动换吸嘴位置");
        }
    }

    private static Location lastConfiguredLocation(Location[] locations) {
        for (int index = locations.length - 1; index >= 0; index--) {
            if (isUsable(locations[index]) && !isOrigin(locations[index])) {
                return locations[index];
            }
        }
        return null;
    }

    private static Location firstDistinctLocation(Location[] locations, Location docking) {
        Location end = docking.convertToUnits(LengthUnit.Millimeters);
        for (Location candidate : locations) {
            if (!isUsable(candidate) || isOrigin(candidate)) {
                continue;
            }
            Location current = candidate.convertToUnits(LengthUnit.Millimeters);
            if (Math.abs(current.getX() - end.getX()) > 0.001
                    || Math.abs(current.getY() - end.getY()) > 0.001) {
                return candidate;
            }
        }
        return docking;
    }

    private static void upsertNozzleChanger(SafetyWorkbenchModel model, NozzleTip nozzleTip,
            Location docking, Location approach, Summary summary) {
        Location end = docking.convertToUnits(LengthUnit.Millimeters);
        Location start = approach.convertToUnits(LengthUnit.Millimeters);
        boolean approachAlongX = Math.abs(end.getX() - start.getX())
                > Math.abs(end.getY() - start.getY());
        SafetyObject object = findOrCreate(model,
                "nozzle-tip:" + nozzleTip.getId() + ":changer:body", "", summary);
        object.type = ObjectType.NOZZLE_CHANGER;
        object.name = nozzleTip.getName() + " · 换嘴专用区";
        object.shape = SafetyWorkbenchModel.Shape.BOX;
        object.width = approachAlongX ? 42 : 30;
        object.depth = approachAlongX ? 30 : 42;
        object.height = 32;
        object.x = end.getX() - object.width / 2.0;
        object.y = end.getY() - object.depth / 2.0;
        object.z = finiteZ(end, model) - object.height;
        object.apertureWidth = approachAlongX ? 26 : 12;
        object.apertureDepth = approachAlongX ? 12 : 26;
        object.clearance = 2;
        object.obstacle = true;
        object.locked = true;
        object.enabled = true;
        object.colorRgb = 0xA669CE;
        model.touch();
    }

    private static void importFeederPickPoints(SafetyWorkbenchModel model, Machine machine,
            Set<String> feederTypeKeys, Summary summary) {
        if (feederTypeKeys != null && feederTypeKeys.isEmpty()) {
            summary.warnings.add("没有选择任何飞达类型，已跳过飞达导入");
            return;
        }
        int points = 0;
        int rearFacing = 0;
        Map<String, Integer> importedByType = new LinkedHashMap<>();
        Map<String, String> labelsByType = new LinkedHashMap<>();
        for (Feeder feeder : machine.getFeeders()) {
            String typeKey = feederTypeKey(feeder);
            if (feederTypeKeys != null && !feederTypeKeys.contains(typeKey)) {
                continue;
            }
            try {
                Location location = feeder.getPickLocation();
                if (!isUsable(location)) {
                    continue;
                }
                upsertFeeder(model, feeder, location, summary);
                points++;
                if (isRearFeeder(model, location)) {
                    rearFacing++;
                }
                labelsByType.put(typeKey, feederTypeLabel(feeder));
                importedByType.put(typeKey, importedByType.getOrDefault(typeKey, 0) + 1);
            }
            catch (Exception error) {
                summary.warnings.add("飞达“" + feeder.getName() + "”取料点读取失败："
                        + error.getMessage());
            }
        }
        if (points > 0) {
            summary.details.add("已同步飞达实体与取料位置 " + points + " 个");
            for (Map.Entry<String, Integer> entry : importedByType.entrySet()) {
                summary.details.add("  " + labelsByType.get(entry.getKey())
                        + "：" + entry.getValue() + " 个");
            }
            if (rearFacing > 0) {
                summary.details.add("已识别并翻转后侧飞达 " + rearFacing
                        + " 个，使取料端朝向工作区");
            }
        }
        else {
            summary.warnings.add(feederTypeKeys == null
                    ? "没有读取到可用的飞达取料点"
                    : "所选飞达类型没有读取到可用的取料点");
        }
    }

    private static void importBottomCameras(SafetyWorkbenchModel model, Machine machine,
            Summary summary) {
        int cameras = 0;
        for (Camera camera : machine.getCameras()) {
            if (camera.getLooking() != Camera.Looking.Up) {
                continue;
            }
            try {
                Location location = camera.getLocation();
                if (!isUsable(location)) {
                    continue;
                }
                upsertBottomCamera(model, camera, location, summary);
                cameras++;
            }
            catch (Exception error) {
                summary.warnings.add("底部相机“" + camera.getName() + "”坐标读取失败："
                        + error.getMessage());
            }
        }
        if (cameras > 0) {
            summary.details.add("已同步底部相机实体 " + cameras + " 个");
        }
        else {
            summary.warnings.add("没有读取到向上看的底部相机");
        }
    }

    private static void upsertFeeder(SafetyWorkbenchModel model, Feeder feeder,
            Location location, Summary summary) {
        Location millimeters = location.convertToUnits(LengthUnit.Millimeters);
        boolean measuredExisting = model.getMeasuredFeederTopZ() != null && model.getObjects().stream()
                .anyMatch(existing -> ("feeder:" + feeder.getId() + ":body").equals(existing.sourceKey));
        SafetyObject object = findOrCreate(model, "feeder:" + feeder.getId() + ":body",
                "feeder:" + feeder.getId() + ":pick", summary);
        object.type = ObjectType.FEEDER;
        object.name = feeder.getName() + (feeder.isEnabled() ? "" : "（未启用）");
        object.shape = SafetyWorkbenchModel.Shape.BOX;
        if (!measuredExisting) {
            object.width = 24;
            object.depth = 120;
            object.height = 18;
        }
        object.x = millimeters.getX() - object.width / 2.0;
        object.rearFacing = isRearFeeder(model, millimeters);
        object.y = millimeters.getY() - object.depth
                * (object.rearFacing ? 0.90 : 0.10);
        object.z = finiteZ(millimeters, model) - object.height;
        if ((feeder instanceof org.openpnp.machine.reference.feeder.ReferenceAutoFeeder
                || feeder instanceof org.openpnp.machine.reference.feeder.ReferenceSlotAutoFeeder)
                && model.getMeasuredFrontFeederMaxY() != null
                && model.getMeasuredRearFeederMinY() != null
                && model.getMeasuredFeederTopZ() != null) {
            object.y = object.rearFacing ? model.getMeasuredRearFeederMinY()
                    : model.getMeasuredFrontFeederMaxY() - object.depth;
            object.z = model.getMeasuredFeederTopZ() - object.height;
        }
        object.clearance = model.getMeasuredFeederTopZ() != null
                && (feeder instanceof org.openpnp.machine.reference.feeder.ReferenceAutoFeeder
                    || feeder instanceof org.openpnp.machine.reference.feeder.ReferenceSlotAutoFeeder) ? 2 : 3;
        object.obstacle = true;
        object.locked = true;
        object.enabled = true;
        object.colorRgb = 0x6E786F;
        model.touch();
    }

    static boolean isRearFeeder(SafetyWorkbenchModel model, Location location) {
        Location millimeters = location.convertToUnits(LengthUnit.Millimeters);
        double middleY = (model.getMinY() + model.getMaxY()) / 2.0;
        return Double.isFinite(millimeters.getY()) && millimeters.getY() > middleY;
    }

    private static void upsertBottomCamera(SafetyWorkbenchModel model, Camera camera,
            Location location, Summary summary) {
        Location millimeters = location.convertToUnits(LengthUnit.Millimeters);
        boolean measuredExisting = model.getMeasuredBottomCameraTopZ() != null && model.getObjects().stream()
                .anyMatch(existing -> ("camera:" + camera.getId() + ":body").equals(existing.sourceKey));
        SafetyObject object = findOrCreate(model, "camera:" + camera.getId() + ":body",
                "", summary);
        object.type = ObjectType.BOTTOM_CAMERA;
        object.name = camera.getName();
        object.shape = SafetyWorkbenchModel.Shape.BOX;
        if (!measuredExisting) {
            object.width = 70;
            object.depth = 70;
            object.height = 25;
        }
        object.x = millimeters.getX() - object.width / 2.0;
        object.y = millimeters.getY() - object.depth / 2.0;
        object.z = finiteZ(millimeters, model) - object.height;
        if (!measuredExisting) {
            object.apertureWidth = 28;
            object.apertureDepth = 28;
        }
        if (model.getMeasuredBottomCameraTopZ() != null) {
            object.z = model.getMeasuredBottomCameraTopZ() - object.height;
        }
        object.clearance = 3;
        object.obstacle = true;
        object.locked = true;
        object.enabled = true;
        object.colorRgb = 0x526774;
        model.touch();
    }

    private static SafetyObject findOrCreate(SafetyWorkbenchModel model, String sourceKey,
            String legacySourceKey, Summary summary) {
        for (SafetyObject candidate : model.getObjects()) {
            if (sourceKey.equals(candidate.sourceKey)
                    || (!legacySourceKey.isEmpty() && legacySourceKey.equals(candidate.sourceKey))) {
                candidate.sourceKey = sourceKey;
                summary.updated++;
                return candidate;
            }
        }
        SafetyObject object = model.createObject(ObjectType.CUSTOM);
        object.sourceKey = sourceKey;
        model.getObjects().add(object);
        summary.created++;
        return object;
    }

    private static double finiteZ(Location location, SafetyWorkbenchModel model) {
        return Double.isFinite(location.getZ()) ? location.getZ() : model.getMinZ();
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
        configureImportedReferenceEntity(object);
        object.obstacle = false;
        object.locked = true;
        object.enabled = true;
        model.touch();
    }

    private static void configureImportedReferenceEntity(SafetyObject object) {
        object.clearance = 0;
        object.apertureWidth = 0;
        object.apertureDepth = 0;
        if (object.type == ObjectType.HOME_POINT) {
            object.shape = SafetyWorkbenchModel.Shape.BOX;
            object.width = 36;
            object.depth = 36;
            object.height = 8;
            object.colorRgb = 0x4E83A8;
        }
        else if (object.type == ObjectType.PARK_POINT) {
            object.shape = SafetyWorkbenchModel.Shape.BOX;
            object.width = 48;
            object.depth = 48;
            object.height = 10;
            object.colorRgb = 0x607F94;
        }
        else if (object.type == ObjectType.DISCARD_POINT) {
            object.shape = SafetyWorkbenchModel.Shape.CYLINDER;
            object.width = 52;
            object.depth = 52;
            object.height = 24;
            object.apertureWidth = 26;
            object.apertureDepth = 26;
            object.colorRgb = 0xA26F50;
        }
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
