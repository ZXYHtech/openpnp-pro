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

import javax.imageio.ImageIO;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openpnp.machine.reference.camera.ReferenceCamera;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine;
import org.openpnp.machine.reference.safety.DynamicSafetyCollisionEngine.DynamicTool;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.ValidationResult;
import org.openpnp.machine.reference.safety.SafetyCollisionEngine.Waypoint;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Camera;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.PartAlignment.PartAlignmentOffset;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.Utils2D;
import org.openpnp.util.VisionUtils;
import org.pmw.tinylog.Logger;

/**
 * Guarded flying-bottom-vision implementation. It captures continuous camera
 * frames during a low-speed pass and evaluates them with the existing vision
 * pipeline. It never owns the fallback decision: callers retain the normal
 * bottom-vision path.
 */
public final class FlyingBottomVisionService {
    private static final long MIN_FRAME_BUFFER_BYTES = 48L * 1024 * 1024;
    private static final long MAX_FRAME_BUFFER_BYTES = 128L * 1024 * 1024;

    public static final class FrameSample {
        private final BufferedImage image;
        private final long nanoTime;
        private final double sharpness;
        private double pathOffsetXmm;
        private boolean registered;

        FrameSample(BufferedImage image, long nanoTime) {
            this.image = image;
            this.nanoTime = nanoTime;
            this.sharpness = FlyingVisionFrameEvaluator.sharpness(image.getWidth() >= 128 && image.getHeight() >= 128
                    ? image.getSubimage(image.getWidth() / 2 - 64, image.getHeight() / 2 - 64, 128, 128) : image);
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
        private final boolean preRotated;
        private final double wantedAngle;

        Result(PartAlignmentOffset offsets, int capturedFrames, int successfulFrames,
                double sharpness, double elapsedMs, boolean preRotated,
                double wantedAngle) {
            this.offsets = offsets;
            this.capturedFrames = capturedFrames;
            this.successfulFrames = successfulFrames;
            this.sharpness = sharpness;
            this.elapsedMs = elapsedMs;
            this.preRotated = preRotated;
            this.wantedAngle = wantedAngle;
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

        public boolean isPreRotated() {
            return preRotated;
        }

        public double getWantedAngle() {
            return wantedAngle;
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
        return execute(alignment, part, null, null, nozzle);
    }

    public static Result execute(ReferenceBottomVision alignment, Part part,
            BoardLocation boardLocation, Placement placement, Nozzle nozzle) throws Exception {
        if (!Configuration.get().getMachine().isEnabled()) {
            throw new Exception("Flying vision requires an enabled machine.");
        }
        if (!Configuration.get().getMachine().isHomed()) {
            throw new Exception("Flying vision requires a homed machine.");
        }
        if (part == null || nozzle == null
                || !part.isInterchangeableWith(nozzle.getPart())) {
            throw new Exception("Flying vision requires the selected part on the nozzle.");
        }
        BottomVisionSettings visionSettings = alignment.getInheritedVisionSettings(part);
        boolean preRotate = usesPreRotate(alignment, visionSettings);
        double currentNozzleAngle = nozzle.getLocation().getRotation();
        double wantedAngle = calculateWantedAngle(preRotate, boardLocation, placement,
                currentNozzleAngle);

        Camera camera = VisionUtils.getBottomVisionCamera();
        long operationStarted = System.nanoTime();
        List<FrameSample> frames = capturePass(alignment, part, nozzle, camera, wantedAngle);
        if (frames.isEmpty()) {
            throw new Exception("没有通过同帧配准与运动方向检查的飞拍帧，请检查照明、参考标定和速度。");
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
        List<PartAlignmentOffset> observations = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            FrameSample frame = candidates.get(index);
            if (frame.getSharpness() < FlyingVisionSettings.getMinimumSharpness()) {
                continue;
            }
            try {
                PartAlignmentOffset offsets = alignment.analyzeExperimentalFrame(
                        part, nozzle, camera, frame.getImage(), wantedAngle, preRotate);
                offsets = correctForMotion(offsets, frame);
                validateOffsets(offsets);
                successful++;
                observations.add(offsets);
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
        bestOffsets = FlyingVisionRegistration.consensus(observations);
        if (FlyingVisionSettings.isSaveDebugImages()) {
            saveFrames(part, candidates, bestFrame);
        }
        try {
            org.openpnp.gui.MainFrame frame = org.openpnp.gui.MainFrame.get();
            if (frame != null) {
                frame.getCameraViews().getCameraView(camera).showFilteredImage(bestFrame.getImage(),
                        "飞拍补偿后 · " + nozzle.getName() + " · " + part.getId()
                                + " · 有效 " + successful + "/" + frames.size()
                                + " · " + bestOffsets.getLocation(), 2000);
            }
        } catch (Exception unavailableView) {
            Logger.debug(unavailableView, "Flying-vision camera view unavailable.");
        }
        double elapsedMs = (System.nanoTime() - operationStarted) / 1_000_000.0;
        Logger.info("Flying bottom vision part={} mode={} angle={} frames={} valid={} sharpness={} elapsedMs={}",
                part.getId(), preRotate ? "pre-rotate" : "post-rotate",
                String.format(Locale.ROOT, "%.2f", wantedAngle),
                frames.size(), successful,
                String.format(Locale.ROOT, "%.2f", bestFrame.getSharpness()),
                String.format(Locale.ROOT, "%.1f", elapsedMs));
        return new Result(bestOffsets, frames.size(), successful,
                bestFrame.getSharpness(), elapsedMs, preRotate, wantedAngle);
    }

    public static List<FrameSample> capturePass(ReferenceBottomVision alignment,
            Part part, Nozzle nozzle, Camera camera) throws Exception {
        return capturePass(alignment, part, nozzle, camera, 0.0);
    }

    public static List<FrameSample> capturePass(ReferenceBottomVision alignment,
            Part part, Nozzle nozzle, Camera camera, double wantedAngle) throws Exception {
        double speed = FlyingVisionSettings.getSpeed();
        Location center = alignment.getCameraLocationAtPartHeight(part, camera, nozzle, wantedAngle)
                .convertToUnits(LengthUnit.Millimeters);
        Location approach = center.derive(
                center.getX() - FlyingVisionSettings.getApproachDistanceMm(),
                null, null, null);
        Location exit = center.derive(
                center.getX() + FlyingVisionSettings.getExitDistanceMm(),
                null, null, null);
        FlyingVisionRegistration.Model registration = FlyingVisionRegistration.load(nozzle, camera, center);
        if (!(camera instanceof ReferenceCamera)) {
            throw new Exception("同帧飞拍需要支持直接连续采集的底部相机。");
        }
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

        List<FrameSample> frames = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch ready = new CountDownLatch(1);
        int capacity = frameBufferCapacity(camera.getWidth(), camera.getHeight(),
                FlyingVisionSettings.getFrameCount(), Runtime.getRuntime().maxMemory());
        Thread capture = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(Configuration.class.getClassLoader());
            double previous = Double.NaN;
            try {
                while (running.get()) {
                    BufferedImage image = ((ReferenceCamera)camera).captureTransformed();
                    long received = System.nanoTime();
                    ready.countDown();
                    try {
                        FlyingVisionRegistration.RegisteredFrame result =
                                FlyingVisionRegistration.register(registration, image, camera);
                        double delta = result.x - previous;
                        previous = result.x;
                        if (!Double.isFinite(delta) || delta < .015) {
                            if (delta < -.02) {
                                frames.clear();
                            }
                            continue;
                        }
                        FrameSample sample = new FrameSample(result.image, received);
                        sample.pathOffsetXmm = result.x;
                        sample.registered = true;
                        synchronized (frames) {
                            frames.add(sample);
                            if (frames.size() > capacity) {
                                FrameSample worst = Collections.max(frames,
                                        Comparator.comparingDouble(f -> Math.abs(f.pathOffsetXmm)));
                                frames.remove(worst);
                            }
                        }
                    } catch (Exception rejected) {
                        // Outside the small validated imaging window is expected during travel.
                    }
                }
            } catch (Throwable failure) {
                errors.add(failure);
            }
        }, "Flying-vision-direct-capture");
        capture.setDaemon(true);
        boolean completed = false;
        try {
            camera.actuateLightBeforeCapture();
            capture.start();
            if (!ready.await(FlyingVisionSettings.getCaptureTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new Exception("飞拍相机未及时返回新图像。");
            }
            // Queue the imaging-height approach and +X pass together, without an approach stop.
            MovableUtils.moveToBottomVisionLocation(nozzle, approach,
                    Configuration.get().getMachine().getSpeed());
            nozzle.moveTo(exit, speed);
            nozzle.waitForCompletion(CompletionType.WaitForStillstand);
            long drainUntil = System.nanoTime() + 150_000_000L;
            while (frames.size() < 4 && System.nanoTime() < drainUntil && capture.isAlive()) {
                Thread.sleep(5);
            }
            running.set(false);
            capture.join(1500);
            if (capture.isAlive() || !errors.isEmpty()) {
                throw new Exception("飞拍采集线程异常，未采用结果。", errors.isEmpty() ? null : errors.get(0));
            }
            synchronized (frames) {
                completed = true;
                return new ArrayList<>(frames);
            }
        }
        finally {
            running.set(false);
            if (capture.isAlive()) {
                capture.join(1500);
            }
            try {
                camera.actuateLightAfterCapture();
            }
            finally {
                if (!completed) {
                    nozzle.moveToSafeZ(Math.min(speed, 0.20));
                    nozzle.waitForCompletion(CompletionType.WaitForStillstand);
                }
            }
        }
    }

    /**
     * Bounds retained flying-vision frames by both the requested candidate count and a small
     * fraction of the Java heap. A 4K BGR frame is roughly 24 MiB, so the previous fixed
     * 30..150-frame buffer could exhaust a 768 MiB heap in a single pass.
     */
    static int frameBufferCapacity(int width, int height, int requestedFrames,
            long maximumHeapBytes) {
        long pixels = Math.max(1L, width) * Math.max(1L, height);
        long estimatedFrameBytes = pixels > Long.MAX_VALUE / 3L
                ? Long.MAX_VALUE : pixels * 3L;
        long heapBudget = Math.max(MIN_FRAME_BUFFER_BYTES,
                Math.min(MAX_FRAME_BUFFER_BYTES, Math.max(1L, maximumHeapBytes) / 8L));
        long byMemory = Math.max(3L, heapBudget / Math.max(1L, estimatedFrameBytes));
        long desired = Math.max(3L, Math.min(60L, Math.max(1L, requestedFrames) * 2L));
        return (int) Math.max(3L, Math.min(desired, byMemory));
    }

    /** Thread-safe temporal decimator. It never queues unbounded camera frames. */
    static final class BoundedFrameCollector {
        private final int requestedFrames;
        private final long maximumHeapBytes;
        private final List<FrameSample> retained = new ArrayList<>();
        private int capacity;
        private long received;
        private long stride = 1;

        BoundedFrameCollector(int requestedFrames, long maximumHeapBytes) {
            this.requestedFrames = requestedFrames;
            this.maximumHeapBytes = maximumHeapBytes;
        }

        synchronized void offer(BufferedImage image, long nanoTime) {
            if (image == null) {
                return;
            }
            long index = received++;
            if (capacity == 0) {
                capacity = frameBufferCapacity(image.getWidth(), image.getHeight(),
                        requestedFrames, maximumHeapBytes);
            }
            if (index % stride != 0) {
                return;
            }
            if (retained.size() >= capacity) {
                compact();
                if (index % stride != 0) {
                    return;
                }
            }
            retained.add(new FrameSample(copy(image), nanoTime));
        }

        private void compact() {
            List<FrameSample> compacted = new ArrayList<>((retained.size() + 1) / 2);
            for (int index = 0; index < retained.size(); index += 2) {
                compacted.add(retained.get(index));
            }
            retained.clear();
            retained.addAll(compacted);
            stride = Math.min(Long.MAX_VALUE / 2, stride * 2);
        }

        synchronized int size() {
            return retained.size();
        }

        synchronized List<FrameSample> snapshot() {
            return new ArrayList<>(retained);
        }
    }

    static boolean usesPreRotate(ReferenceBottomVision alignment,
            BottomVisionSettings settings) {
        return usesPreRotate(settings.getPreRotateUsage(), alignment.isPreRotate());
    }

    static boolean usesPreRotate(ReferenceBottomVision.PreRotateUsage usage,
            boolean defaultPreRotate) {
        return usage == ReferenceBottomVision.PreRotateUsage.AlwaysOn
                || (usage == ReferenceBottomVision.PreRotateUsage.Default && defaultPreRotate);
    }

    static double calculateWantedAngle(boolean preRotate, BoardLocation boardLocation,
            Placement placement, double currentNozzleAngle) {
        if (!preRotate) {
            return 0.0;
        }
        double wantedAngle = currentNozzleAngle;
        if (placement != null) {
            wantedAngle = placement.getLocation().getRotation();
            if (boardLocation != null) {
                wantedAngle = Utils2D.calculateBoardPlacementLocation(
                        boardLocation, placement.getLocation()).getRotation();
            }
        }
        if (!Double.isFinite(wantedAngle)) {
            wantedAngle = 0.0;
        }
        return Utils2D.angleNorm(wantedAngle, 180.0);
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
        // Registered images have already been restored to the nominal camera reference.
        return frame.registered ? result : correctForMotion(result, frame.getPathOffsetXmm());
    }

    static PartAlignmentOffset correctForMotion(PartAlignmentOffset result,
            double pathOffsetXmm) {
        Location location = result.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location pathOffset = new Location(LengthUnit.Millimeters,
                pathOffsetXmm, 0, 0, 0);
        if (result.getPreRotated()) {
            // Pre-rotate analysis rotates the observed center offset by the
            // residual angle. Apply the same transform to the known path motion
            // before removing it, otherwise X travel leaks into Y correction.
            pathOffset = pathOffset.rotateXy(-location.getRotation());
        }
        Location corrected = location.derive(
                location.getX() - pathOffset.getX(),
                location.getY() - pathOffset.getY(), null, null);
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
