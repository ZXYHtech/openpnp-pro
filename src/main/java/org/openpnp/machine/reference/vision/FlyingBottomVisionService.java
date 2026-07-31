/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.vision;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.imageio.ImageIO;

import org.openpnp.CameraListener;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine.DynamicTool;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.ValidationResult;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.VisionUtils;
import org.pmw.tinylog.Logger;

/**
 * Guarded flying-bottom-vision implementation. It captures continuous camera
 * frames during a low-speed pass and evaluates them with the existing vision
 * pipeline. It never owns the fallback decision: callers retain the normal
 * bottom-vision path.
 */
public final class FlyingBottomVisionService {
    public static final class FrameSample {
        private final BufferedImage image;
        private final long nanoTime;
        private final double sharpness;
        private double pathOffsetXmm;

        FrameSample(BufferedImage image, long nanoTime) {
            this.image = image;
            this.nanoTime = nanoTime;
            this.sharpness = FlyingVisionFrameEvaluator.sharpness(image);
        }

        public BufferedImage getImage() {
            return image;
        }

        public long getNanoTime() {
            return nanoTime;
        }

        public double getSharpness() {
            return sharpness;
        }

        public double getPathOffsetXmm() {
            return pathOffsetXmm;
        }
    }

    public static final class Result {
        private final PartAlignmentOffset offsets;
        private final int capturedFrames;
        private final int successfulFrames;
        private final double sharpness;
        private final double elapsedMs;

        Result(PartAlignmentOffset offsets, int capturedFrames, int successfulFrames,
                double sharpness, double elapsedMs) {
            this.offsets = offsets;
            this.capturedFrames = capturedFrames;
            this.successfulFrames = successfulFrames;
            this.sharpness = sharpness;
            this.elapsedMs = elapsedMs;
        }

        public PartAlignmentOffset getOffsets() {
            return offsets;
        }

        public int getCapturedFrames() {
            return capturedFrames;
        }

        public int getSuccessfulFrames() {
            return successfulFrames;
        }

        public double getSharpness() {
            return sharpness;
        }

        public double getElapsedMs() {
            return elapsedMs;
        }
    }

    private FlyingBottomVisionService() {
    }

    public static boolean shouldAttempt(Part part) {
        return FlyingVisionSettings.shouldUseFor(part)
                && SafetyWorkbenchStore.get().isCertified();
    }

    public static Result execute(ReferenceBottomVision alignment, Part part,
            Nozzle nozzle) throws Exception {
        if (!Configuration.get().getMachine().isEnabled()) {
            throw new Exception("Flying vision requires an enabled machine.");
        }
        if (!Configuration.get().getMachine().isHomed()) {
            throw new Exception("Flying vision requires a homed machine.");
        }
        if (part == null || nozzle == null || nozzle.getPart() != part) {
            throw new Exception("Flying vision requires the selected part on the nozzle.");
        }
        BottomVisionSettings visionSettings = alignment.getInheritedVisionSettings(part);
        boolean preRotate = visionSettings.getPreRotateUsage()
                == ReferenceBottomVision.PreRotateUsage.AlwaysOn
                || (visionSettings.getPreRotateUsage()
                        == ReferenceBottomVision.PreRotateUsage.Default
                        && alignment.isPreRotate());
        if (preRotate) {
            throw new Exception("Flying vision currently requires post-rotate bottom vision.");
        }

        Camera camera = VisionUtils.getBottomVisionCamera();
        long operationStarted = System.nanoTime();
        List<FrameSample> frames = capturePass(alignment, part, nozzle, camera);
        if (frames.isEmpty()) {
            throw new Exception("No camera frames were received during the flying pass.");
        }

        long centerTime = estimateCenterTime(frames);
        List<FrameSample> candidates = new ArrayList<>(frames);
        candidates.sort(Comparator
                .comparingDouble((FrameSample sample) -> candidateScore(sample, centerTime))
                .reversed());
        int limit = Math.min(FlyingVisionSettings.getFrameCount(), candidates.size());
        int successful = 0;
        PartAlignmentOffset bestOffsets = null;
        FrameSample bestFrame = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Exception lastFailure = null;
        for (int index = 0; index < limit; index++) {
            FrameSample frame = candidates.get(index);
            if (frame.getSharpness() < FlyingVisionSettings.getMinimumSharpness()) {
                continue;
            }
            try {
                PartAlignmentOffset offsets = alignment.analyzeExperimentalFrame(
                        part, nozzle, camera, frame.getImage());
                offsets = correctForMotion(offsets, frame);
                validateOffsets(offsets);
                successful++;
                double score = candidateScore(frame, centerTime);
                if (score > bestScore) {
                    bestScore = score;
                    bestOffsets = offsets;
                    bestFrame = frame;
                }
            }
            catch (Exception exception) {
                lastFailure = exception;
                Logger.debug(exception, "Flying vision rejected frame {} for part {}.",
                        index, part.getId());
            }
        }
        if (bestOffsets == null) {
            throw new Exception("No flying-vision frame passed the configured pipeline and limits.",
                    lastFailure);
        }
        if (FlyingVisionSettings.isSaveDebugImages()) {
            saveFrames(part, candidates, bestFrame);
        }
        double elapsedMs = (System.nanoTime() - operationStarted) / 1_000_000.0;
        Logger.info("Flying bottom vision part={} frames={} valid={} sharpness={} elapsedMs={}",
                part.getId(), frames.size(), successful,
                String.format(Locale.ROOT, "%.2f", bestFrame.getSharpness()),
                String.format(Locale.ROOT, "%.1f", elapsedMs));
        return new Result(bestOffsets, frames.size(), successful,
                bestFrame.getSharpness(), elapsedMs);
    }

    public static List<FrameSample> capturePass(ReferenceBottomVision alignment,
            Part part, Nozzle nozzle, Camera camera) throws Exception {
        double speed = FlyingVisionSettings.getSpeed();
        Location center = alignment.getCameraLocationAtPartHeight(part, camera, nozzle, 0.)
                .convertToUnits(LengthUnit.Millimeters);
        Location approach = center.derive(
                center.getX() - FlyingVisionSettings.getApproachDistanceMm(),
                null, null, null);
        Location exit = center.derive(
                center.getX() + FlyingVisionSettings.getExitDistanceMm(),
                null, null, null);
        if (!nozzle.isReachable(approach) || !nozzle.isReachable(exit)) {
            throw new Exception("Flying-vision path exceeds the nozzle reach or soft limits.");
        }
        SafetyWorkbenchModel workbench = SafetyWorkbenchStore.get();
        if (!workbench.isCertified()) {
            throw new Exception("Flying vision is locked until the 3D safety workbench is certified.");
        }
        Location current = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        double safeZ = nozzle.getEffectiveSafeZ() == null
                ? workbench.getMaxZ() - workbench.getToolMaxZOffset()
                : nozzle.getEffectiveSafeZ().convertToUnits(LengthUnit.Millimeters).getValue();
        List<Waypoint> guardedPath = new ArrayList<>();
        guardedPath.add(new Waypoint(current.getX(), current.getY(), current.getZ()));
        guardedPath.add(new Waypoint(current.getX(), current.getY(), safeZ));
        guardedPath.add(new Waypoint(approach.getX(), approach.getY(), safeZ));
        guardedPath.add(new Waypoint(approach.getX(), approach.getY(), approach.getZ()));
        guardedPath.add(new Waypoint(exit.getX(), exit.getY(), exit.getZ()));
        guardedPath.add(new Waypoint(exit.getX(), exit.getY(), safeZ));
        List<DynamicTool> machineTools = DynamicSafetyCollisionEngine.snapshot(
                workbench, Configuration.get().getMachine(), nozzle);
        ValidationResult safety = DynamicSafetyCollisionEngine.validate(
                workbench, guardedPath, machineTools);
        if (!safety.isSafe()) {
            throw new Exception("Flying-vision path rejected by the 3D safety workbench: "
                    + String.join("; ", safety.getIssues()));
        }

        MovableUtils.moveToLocationAtSafeZ(nozzle, approach, Math.min(speed, 0.20));
        nozzle.waitForCompletion(CompletionType.WaitForStillstand);
        List<FrameSample> frames = new CopyOnWriteArrayList<>();
        int captureLimit = Math.max(30, FlyingVisionSettings.getFrameCount() * 5);
        CameraListener listener = image -> {
            if (image != null && frames.size() < captureLimit) {
                frames.add(new FrameSample(copy(image), System.nanoTime()));
            }
        };
        try {
            camera.actuateLightBeforeCapture();
            camera.startContinuousCapture(listener);
            long started = System.nanoTime();
            nozzle.moveTo(exit, speed);
            nozzle.waitForCompletion(CompletionType.WaitForStillstand);
            long finished = System.nanoTime();
            long timeoutAt = System.currentTimeMillis()
                    + FlyingVisionSettings.getCaptureTimeoutMs();
            while (frames.size() < 3 && System.currentTimeMillis() < timeoutAt) {
                Thread.sleep(20);
            }
            frames.removeIf(frame -> frame.getNanoTime() < started
                    || frame.getNanoTime() > finished + 150_000_000L);
            double duration = Math.max(1, finished - started);
            double latencyNs = FlyingVisionSettings.isTimestampCompensationEnabled()
                    ? FlyingVisionSettings.getCameraLatencyMs() * 1_000_000.0 : 0;
            double distance = FlyingVisionSettings.getApproachDistanceMm()
                    + FlyingVisionSettings.getExitDistanceMm();
            for (FrameSample frame : frames) {
                double exposureTime = frame.getNanoTime() - latencyNs;
                double fraction = Math.max(0, Math.min(1,
                        (exposureTime - started) / duration));
                frame.pathOffsetXmm = -FlyingVisionSettings.getApproachDistanceMm()
                        + distance * fraction;
            }
        }
        finally {
            try {
                camera.stopContinuousCapture(listener);
            }
            finally {
                try {
                    camera.actuateLightAfterCapture();
                }
                finally {
                    nozzle.moveToSafeZ(Math.min(speed, 0.20));
                    nozzle.waitForCompletion(CompletionType.WaitForStillstand);
                }
            }
        }
        return new ArrayList<>(frames);
    }

    private static long estimateCenterTime(List<FrameSample> frames) {
        long first = frames.get(0).getNanoTime();
        long last = frames.get(frames.size() - 1).getNanoTime();
        double fraction = FlyingVisionSettings.getApproachDistanceMm()
                / (FlyingVisionSettings.getApproachDistanceMm()
                        + FlyingVisionSettings.getExitDistanceMm());
        long center = first + Math.round((last - first) * fraction);
        if (FlyingVisionSettings.isTimestampCompensationEnabled()) {
            center += Math.round(FlyingVisionSettings.getCameraLatencyMs() * 1_000_000.0);
        }
        return center;
    }

    private static double candidateScore(FrameSample sample, long centerTime) {
        double distanceMs = Math.abs(sample.getNanoTime() - centerTime) / 1_000_000.0;
        return sample.getSharpness() * 10.0 - distanceMs
                - Math.abs(sample.getPathOffsetXmm()) * 25.0;
    }

    private static PartAlignmentOffset correctForMotion(PartAlignmentOffset result,
            FrameSample frame) {
        Location location = result.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location corrected = location.derive(
                location.getX() - frame.getPathOffsetXmm(), null, null, null);
        return new PartAlignmentOffset(corrected, result.getPreRotated());
    }

    private static void validateOffsets(PartAlignmentOffset result) throws Exception {
        Location location = result.getLocation().convertToUnits(LengthUnit.Millimeters);
        double linear = Math.hypot(location.getX(), location.getY());
        if (linear > FlyingVisionSettings.getMaximumOffsetMm()) {
            throw new Exception(String.format(Locale.ROOT,
                    "Flying-vision offset %.3f mm exceeds %.3f mm.",
                    linear, FlyingVisionSettings.getMaximumOffsetMm()));
        }
        if (Math.abs(location.getRotation()) > FlyingVisionSettings.getMaximumAngleDeg()) {
            throw new Exception(String.format(Locale.ROOT,
                    "Flying-vision angle %.3f° exceeds %.3f°.",
                    location.getRotation(), FlyingVisionSettings.getMaximumAngleDeg()));
        }
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = target.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return target;
    }

    private static void saveFrames(Part part, List<FrameSample> frames,
            FrameSample bestFrame) {
        try {
            File root = new File(Configuration.get().getConfigurationDirectory(),
                    "pro-flying-vision");
            File directory = new File(root,
                    new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date())
                            + "-" + safeName(part.getId()));
            if (!directory.mkdirs() && !directory.isDirectory()) {
                return;
            }
            int index = 0;
            for (FrameSample frame : frames) {
                String marker = frame == bestFrame ? "-best" : "";
                ImageIO.write(frame.getImage(), "png",
                        new File(directory, String.format(Locale.ROOT,
                                "%02d-sharp-%.2f%s.png", index++,
                                frame.getSharpness(), marker)));
            }
        }
        catch (Exception exception) {
            Logger.warn(exception, "Unable to save flying-vision debug frames.");
        }
    }

    private static String safeName(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
