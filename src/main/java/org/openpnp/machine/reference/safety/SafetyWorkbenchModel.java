/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent digital-twin model used to gate experimental high-speed motion.
 * All coordinates and dimensions are expressed in millimeters.
 */
public class SafetyWorkbenchModel {
    public enum ObjectType {
        FEEDER("飞达"),
        PLACEMENT_HEAD("贴装头"),
        BOTTOM_CAMERA("底部相机"),
        LOOSE_PARTS_FEEDER("散料飞达"),
        BOARD("待贴电路板"),
        KEEP_OUT("禁入区"),
        NOZZLE_CHANGER("自动换嘴坞"),
        HOME_POINT("Home 点"),
        PARK_POINT("停靠点"),
        NOZZLE_CHANGER_POINT("自动换吸嘴点"),
        DISCARD_POINT("抛料点"),
        FEEDER_PICK_POINT("飞达取料点"),
        CUSTOM("自定义物体");

        private final String displayName;

        ObjectType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isReferencePoint() {
            return this == HOME_POINT || this == PARK_POINT
                    || this == NOZZLE_CHANGER_POINT || this == DISCARD_POINT
                    || this == FEEDER_PICK_POINT;
        }
    }

    public enum Shape {
        BOX("方体"),
        CYLINDER("圆柱"),
        POLYGON("自定义轮廓");

        private final String displayName;

        Shape(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static class Point2 {
        public double x;
        public double y;

        public Point2() {
        }

        public Point2(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    public static class ToolProfile {
        public String id = UUID.randomUUID().toString();
        public String headId = "";
        public String toolId = "";
        public String name = "N1";
        public boolean enabled = true;
        public double bodyRadiusX = 16;
        public double bodyRadiusY = 16;
        public double bodyMinZOffset = 4;
        public double bodyMaxZOffset = 72;
        public boolean bodyFollowsNozzleZ;
        /** True when the machine Z coordinate already locates the calibrated nozzle tip. */
        public boolean zIsNozzleTip;
        public double shaftRadius = 3;
        public double clearance = 2;
        public double retractedZ = 0;
        public double extendedZ = -30;
        public double retractedTipLength = 8;
        public double extendedTipLength = 38;
        public int colorRgb = 0x70889A;

        public double getTipLength(double currentZ) {
            double denominator = extendedZ - retractedZ;
            if (Math.abs(denominator) < 0.000001) {
                return Math.max(retractedTipLength, extendedTipLength);
            }
            double fraction = (currentZ - retractedZ) / denominator;
            fraction = Math.max(0, Math.min(1, fraction));
            return retractedTipLength
                    + (extendedTipLength - retractedTipLength) * fraction;
        }

        public double getRadiusX(double carriedPartRadiusX) {
            return Math.max(bodyRadiusX, carriedPartRadiusX) + clearance;
        }

        public double getRadiusY(double carriedPartRadiusY) {
            return Math.max(bodyRadiusY, carriedPartRadiusY) + clearance;
        }

        public double getMinimumZOffset(double currentZ, double carriedPartHeight) {
            return Math.min(bodyMinZOffset,
                    -(zIsNozzleTip ? 0 : getTipLength(currentZ)) - Math.max(0, carriedPartHeight) - clearance);
        }

        public double getMaximumZOffset() {
            return bodyMaxZOffset + clearance;
        }

        @Override
        public String toString() {
            return name;
        }
    }
    public static class SafetyObject {
        public String id = UUID.randomUUID().toString();
        public String name = "新物体";
        public ObjectType type = ObjectType.CUSTOM;
        public Shape shape = Shape.BOX;
        public double x;
        public double y;
        public double z;
        public double width = 30;
        public double depth = 30;
        public double height = 20;
        public double clearance = 2;
        public double apertureWidth;
        public double apertureDepth;
        public boolean obstacle = true;
        public boolean enabled = true;
        public boolean locked;
        /** Rear-side feeders are mirrored along Y so their pick end faces the work area. */
        public boolean rearFacing;
        public int colorRgb = 0x71879A;
        public String sourceKey = "";
        public List<Point2> polygon = new ArrayList<>();

        public SafetyObject copy() {
            SafetyObject copy = new SafetyObject();
            copy.id = UUID.randomUUID().toString();
            copy.name = name + " 副本";
            copy.type = type;
            copy.shape = shape;
            copy.x = x + 5;
            copy.y = y + 5;
            copy.z = z;
            copy.width = width;
            copy.depth = depth;
            copy.height = height;
            copy.clearance = clearance;
            copy.apertureWidth = apertureWidth;
            copy.apertureDepth = apertureDepth;
            copy.obstacle = obstacle;
            copy.enabled = enabled;
            copy.locked = false;
            copy.rearFacing = rearFacing;
            copy.colorRgb = colorRgb;
            for (Point2 point : polygon) {
                copy.polygon.add(new Point2(point.x, point.y));
            }
            return copy;
        }
    }

    private int schemaVersion = 4;
    private volatile long revision = 1;
    private volatile long certifiedRevision;
    private long certifiedAt;
    private String certificationNote = "";

    private double minX = 0;
    private double maxX = 500;
    private double minY = 0;
    private double maxY = 400;
    private double minZ = -50;
    private double maxZ = 150;
    private Double importedSafeZLow;
    private Double importedSafeZHigh;
    private boolean visionHeightCompensationEnabled;
    /** Optional, operator-certified nozzle-reference-Z floor for XY travel. Never bypasses swept collision checks. */
    private boolean fastTravelFloorEnabled;
    private boolean xyBoundsAreTravelLimits;
    private double fastTravelFloorZ = -5.0;
    // Optional machine-specific measurements; null retains generic import behavior.
    private Double measuredFrontFeederMaxY;
    private Double measuredRearFeederMinY;
    private Double measuredFeederTopZ;
    private Double measuredBottomCameraTopZ;

    public void setMeasuredImportGeometry(double front, double rear, double feederTop, double cameraTop) {
        if (!Double.isFinite(front + rear + feederTop + cameraTop) || front >= rear) {
            throw new IllegalArgumentException("Invalid measured import geometry");
        }
        measuredFrontFeederMaxY = front;
        measuredRearFeederMinY = rear;
        measuredFeederTopZ = feederTop;
        measuredBottomCameraTopZ = cameraTop;
        touch();
    }

    public Double getMeasuredFrontFeederMaxY() { return measuredFrontFeederMaxY; }
    public Double getMeasuredRearFeederMinY() { return measuredRearFeederMinY; }
    public Double getMeasuredFeederTopZ() { return measuredFeederTopZ; }
    public Double getMeasuredBottomCameraTopZ() { return measuredBottomCameraTopZ; }

    private double toolRadiusX = 15;
    private double toolRadiusY = 15;
    private double toolMinZOffset = -5;
    private double toolMaxZOffset = 70;
    private double sampleStep = 1.0;
    private final List<ToolProfile> toolProfiles = new ArrayList<>();
    private final List<SafetyObject> objects = new ArrayList<>();

    public static SafetyWorkbenchModel createDefault() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        SafetyObject camera = model.createObject(ObjectType.BOTTOM_CAMERA);
        camera.name = "底部相机（示例，需标定）";
        camera.x = 220;
        camera.y = 180;
        camera.z = 0;
        camera.width = 80;
        camera.depth = 80;
        camera.height = 25;
        camera.apertureWidth = 42;
        camera.apertureDepth = 42;
        camera.clearance = 3;
        model.objects.add(camera);
        return model;
    }

    public SafetyObject createObject(ObjectType type) {
        SafetyObject object = new SafetyObject();
        object.type = type;
        object.name = type.getDisplayName();
        switch (type) {
            case FEEDER:
                object.width = 35;
                object.depth = 120;
                object.height = 35;
                object.colorRgb = 0x668A73;
                break;
            case PLACEMENT_HEAD:
                object.width = 55;
                object.depth = 55;
                object.height = 90;
                object.obstacle = false;
                object.colorRgb = 0x61788A;
                break;
            case BOTTOM_CAMERA:
                object.width = 80;
                object.depth = 80;
                object.height = 25;
                object.apertureWidth = 42;
                object.apertureDepth = 42;
                object.colorRgb = 0x445A6A;
                break;
            case LOOSE_PARTS_FEEDER:
                object.width = 100;
                object.depth = 80;
                object.height = 15;
                object.colorRgb = 0x8A8066;
                break;
            case BOARD:
                object.width = 120;
                object.depth = 80;
                object.height = 2;
                object.clearance = 1;
                object.colorRgb = 0x52755F;
                break;
            case KEEP_OUT:
                object.width = 50;
                object.depth = 50;
                object.height = 100;
                object.clearance = 0;
                object.colorRgb = 0x8A5656;
                break;
            case NOZZLE_CHANGER:
                object.width = 30;
                object.depth = 42;
                object.height = 32;
                object.apertureWidth = 12;
                object.apertureDepth = 26;
                object.clearance = 2;
                object.colorRgb = 0x52585C;
                break;
            case HOME_POINT:
                configureReferencePoint(object, 0x4E83A8, 36, 8);
                object.shape = Shape.BOX;
                break;
            case PARK_POINT:
                configureReferencePoint(object, 0x607F94, 48, 10);
                object.shape = Shape.BOX;
                break;
            case NOZZLE_CHANGER_POINT:
                configureReferencePoint(object, 0x8D739E, 9, 7);
                break;
            case DISCARD_POINT:
                configureReferencePoint(object, 0xA26F50, 52, 24);
                object.apertureWidth = 26;
                object.apertureDepth = 26;
                break;
            case FEEDER_PICK_POINT:
                configureReferencePoint(object, 0x6F9A78, 7, 4);
                break;
            case CUSTOM:
            default:
                object.colorRgb = 0x71879A;
                break;
        }
        return object;
    }

    private static void configureReferencePoint(SafetyObject object, int color,
            double size, double markerHeight) {
        object.width = size;
        object.depth = size;
        object.height = markerHeight;
        object.clearance = 0;
        object.obstacle = false;
        object.locked = true;
        object.shape = Shape.CYLINDER;
        object.colorRgb = color;
    }

    public void addObject(SafetyObject object) {
        objects.add(object);
        touch();
    }

    public void removeObject(SafetyObject object) {
        if (objects.remove(object)) {
            touch();
        }
    }

    public void touch() {
        revision++;
        certifiedRevision = 0;
        certifiedAt = 0;
        certificationNote = "";
    }

    public void certify(String note) {
        schemaVersion = 4;
        certifiedRevision = revision;
        certifiedAt = System.currentTimeMillis();
        certificationNote = note == null ? "" : note;
    }

    public boolean isCertified() {
        return schemaVersion >= 2 && !toolProfiles.isEmpty()
                && certifiedRevision != 0 && certifiedRevision == revision;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public long getRevision() {
        return revision;
    }

    public long getCertifiedRevision() {
        return certifiedRevision;
    }

    public long getCertifiedAt() {
        return certifiedAt;
    }

    public String getCertificationNote() {
        return certificationNote;
    }

    public double getMinX() {
        return minX;
    }

    public void setMinX(double minX) {
        this.minX = minX;
        touch();
    }

    public double getMaxX() {
        return maxX;
    }

    public void setMaxX(double maxX) {
        this.maxX = maxX;
        touch();
    }

    public double getMinY() {
        return minY;
    }

    public void setMinY(double minY) {
        this.minY = minY;
        touch();
    }

    public double getMaxY() {
        return maxY;
    }

    public void setMaxY(double maxY) {
        this.maxY = maxY;
        touch();
    }

    public double getMinZ() {
        return minZ;
    }

    public void setMinZ(double minZ) {
        this.minZ = minZ;
        touch();
    }

    public double getMaxZ() {
        return maxZ;
    }

    public void setMaxZ(double maxZ) {
        this.maxZ = maxZ;
        touch();
    }

    /** Disabled for existing machine profiles until the actual -5 mm clearance is measured and certified. */
    public boolean isFastTravelFloorEnabled() {
        return fastTravelFloorEnabled;
    }

    public boolean isXyBoundsAreTravelLimits() { return xyBoundsAreTravelLimits; }

    public void setXyBoundsAreTravelLimits(boolean enabled) {
        if (xyBoundsAreTravelLimits != enabled) {
            xyBoundsAreTravelLimits = enabled;
            touch();
        }
    }

    public double getFastTravelFloorZ() {
        return fastTravelFloorZ;
    }

    public void setFastTravelFloor(boolean enabled, double floorZ) {
        if (!Double.isFinite(floorZ) || floorZ <= minZ || floorZ >= maxZ) {
            throw new IllegalArgumentException("快速进场 Z 必须在已配置的工作台 Z 范围内。");
        }
        if (fastTravelFloorEnabled != enabled || fastTravelFloorZ != floorZ) {
            fastTravelFloorEnabled = enabled;
            fastTravelFloorZ = floorZ;
            touch(); // Never reuse an earlier geometry certification after changing this threshold.
        }
    }

    public boolean hasImportedSafeZ() {
        return importedSafeZLow != null && importedSafeZHigh != null
                && Double.isFinite(importedSafeZLow) && Double.isFinite(importedSafeZHigh);
    }

    public double getImportedSafeZLow() {
        return importedSafeZLow;
    }

    public double getImportedSafeZHigh() {
        return importedSafeZHigh;
    }

    public boolean isVisionHeightCompensationEnabled() {
        return visionHeightCompensationEnabled;
    }

    public void setVisionHeightCompensationEnabled(boolean enabled) {
        if (visionHeightCompensationEnabled != enabled) {
            visionHeightCompensationEnabled = enabled;
            schemaVersion = 4;
            touch();
        }
    }

    public void setImportedSafeZ(double low, double high) {
        importedSafeZLow = Math.min(low, high);
        importedSafeZHigh = Math.max(low, high);
        touch();
    }

    public double getToolRadiusX() {
        return toolRadiusX;
    }

    public void setToolRadiusX(double toolRadiusX) {
        this.toolRadiusX = toolRadiusX;
        touch();
    }

    public double getToolRadiusY() {
        return toolRadiusY;
    }

    public void setToolRadiusY(double toolRadiusY) {
        this.toolRadiusY = toolRadiusY;
        touch();
    }

    public double getToolMinZOffset() {
        return toolMinZOffset;
    }

    public void setToolMinZOffset(double toolMinZOffset) {
        this.toolMinZOffset = toolMinZOffset;
        touch();
    }

    public double getToolMaxZOffset() {
        return toolMaxZOffset;
    }

    public void setToolMaxZOffset(double toolMaxZOffset) {
        this.toolMaxZOffset = toolMaxZOffset;
        touch();
    }

    public double getSampleStep() {
        return sampleStep;
    }

    public void setSampleStep(double sampleStep) {
        this.sampleStep = sampleStep;
        touch();
    }

    public List<ToolProfile> getToolProfiles() {
        return toolProfiles;
    }

    public ToolProfile findToolProfile(String headId, String toolId) {
        for (ToolProfile profile : toolProfiles) {
            if (profile != null && profile.headId.equals(headId)
                    && profile.toolId.equals(toolId)) {
                return profile;
            }
        }
        return null;
    }

    public ToolProfile ensureToolProfile(String headId, String toolId, String displayName) {
        ToolProfile profile = findToolProfile(headId, toolId);
        if (profile != null) {
            return profile;
        }
        schemaVersion = 4;
        profile = new ToolProfile();
        profile.headId = headId == null ? "" : headId;
        profile.toolId = toolId == null ? "" : toolId;
        profile.name = displayName == null || displayName.trim().isEmpty()
                ? profile.toolId : displayName;
        toolProfiles.add(profile);
        touch();
        return profile;
    }
    public List<SafetyObject> getObjects() {
        return objects;
    }

    /** Restores an editor snapshot while forcing renewed safety certification. */
    void restoreFrom(SafetyWorkbenchModel source) {
        if (source == null) {
            throw new IllegalArgumentException("工作台历史快照不能为空。");
        }
        schemaVersion = Math.max(4, source.schemaVersion);
        minX = source.minX;
        maxX = source.maxX;
        minY = source.minY;
        maxY = source.maxY;
        minZ = source.minZ;
        maxZ = source.maxZ;
        importedSafeZLow = source.importedSafeZLow;
        importedSafeZHigh = source.importedSafeZHigh;
        visionHeightCompensationEnabled = source.visionHeightCompensationEnabled;
        fastTravelFloorEnabled = source.fastTravelFloorEnabled;
        xyBoundsAreTravelLimits = source.xyBoundsAreTravelLimits;
        fastTravelFloorZ = source.fastTravelFloorZ;
        measuredFrontFeederMaxY = source.measuredFrontFeederMaxY;
        measuredRearFeederMinY = source.measuredRearFeederMinY;
        measuredFeederTopZ = source.measuredFeederTopZ;
        measuredBottomCameraTopZ = source.measuredBottomCameraTopZ;
        toolRadiusX = source.toolRadiusX;
        toolRadiusY = source.toolRadiusY;
        toolMinZOffset = source.toolMinZOffset;
        toolMaxZOffset = source.toolMaxZOffset;
        sampleStep = source.sampleStep;
        toolProfiles.clear();
        toolProfiles.addAll(source.toolProfiles);
        objects.clear();
        objects.addAll(source.objects);
        revision = Math.max(revision, source.revision) + 1;
        certifiedRevision = 0;
        certifiedAt = 0;
        certificationNote = "";
    }
}
