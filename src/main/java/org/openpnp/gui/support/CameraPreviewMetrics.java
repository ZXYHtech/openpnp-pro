/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-safe, sliding-window measurements for experimental camera preview diagnostics.
 * The camera callback only performs a very small image sample hash and timestamp bookkeeping.
 */
public class CameraPreviewMetrics {
    private static final long DEFAULT_WINDOW_NANOS = 4_000_000_000L;

    private final long windowNanos;
    private final Deque<Long> frameTimes = new ArrayDeque<>();
    private final Deque<Long> displayTimes = new ArrayDeque<>();
    private final Deque<TimedValue> rawCaptureDurations = new ArrayDeque<>();
    private final Deque<TimedValue> captureDurations = new ArrayDeque<>();
    private final Deque<TimedValue> processingDurations = new ArrayDeque<>();
    private final Deque<TimedValue> previewLoopDurations = new ArrayDeque<>();
    private final Deque<TimedValue> renderDurations = new ArrayDeque<>();

    private long startedNanos;
    private long receivedFrames;
    private long displayedFrames;
    private long droppedFrames;
    private long duplicateFrames;
    private long lastFingerprint;
    private boolean haveFingerprint;

    public CameraPreviewMetrics() {
        this(DEFAULT_WINDOW_NANOS);
    }

    CameraPreviewMetrics(long windowNanos) {
        this.windowNanos = Math.max(100_000_000L, windowNanos);
        reset(System.nanoTime());
    }

    public synchronized void reset(long nowNanos) {
        startedNanos = nowNanos;
        receivedFrames = 0;
        displayedFrames = 0;
        droppedFrames = 0;
        duplicateFrames = 0;
        haveFingerprint = false;
        frameTimes.clear();
        displayTimes.clear();
        rawCaptureDurations.clear();
        captureDurations.clear();
        processingDurations.clear();
        previewLoopDurations.clear();
        renderDurations.clear();
    }

    public synchronized void recordFrame(BufferedImage image, long nowNanos) {
        receivedFrames++;
        frameTimes.addLast(nowNanos);
        trimTimes(frameTimes, nowNanos);
        if (image != null) {
            long fingerprint = fingerprint(image);
            if (haveFingerprint && fingerprint == lastFingerprint) {
                duplicateFrames++;
            }
            lastFingerprint = fingerprint;
            haveFingerprint = true;
        }
    }

    public synchronized void recordDisplay(long nowNanos) {
        displayedFrames++;
        displayTimes.addLast(nowNanos);
        trimTimes(displayTimes, nowNanos);
    }

    public synchronized void recordDroppedFrame() {
        droppedFrames++;
    }

    public synchronized void recordCaptureDuration(double milliseconds, long nowNanos) {
        if (!Double.isFinite(milliseconds) || milliseconds < 0) {
            return;
        }
        captureDurations.addLast(new TimedValue(nowNanos, milliseconds));
        trimValues(captureDurations, nowNanos);
    }

    public synchronized void recordRawCaptureDuration(double milliseconds, long nowNanos) {
        if (!Double.isFinite(milliseconds) || milliseconds < 0) {
            return;
        }
        rawCaptureDurations.addLast(new TimedValue(nowNanos, milliseconds));
        trimValues(rawCaptureDurations, nowNanos);
    }

    public synchronized void recordProcessingDuration(double milliseconds, long nowNanos) {
        if (!Double.isFinite(milliseconds) || milliseconds < 0) {
            return;
        }
        processingDurations.addLast(new TimedValue(nowNanos, milliseconds));
        trimValues(processingDurations, nowNanos);
    }

    public synchronized void recordPreviewLoopDuration(double milliseconds, long nowNanos) {
        if (!Double.isFinite(milliseconds) || milliseconds < 0) {
            return;
        }
        previewLoopDurations.addLast(new TimedValue(nowNanos, milliseconds));
        trimValues(previewLoopDurations, nowNanos);
    }

    public synchronized void recordRenderDuration(double milliseconds, long nowNanos) {
        if (!Double.isFinite(milliseconds) || milliseconds < 0) {
            return;
        }
        renderDurations.addLast(new TimedValue(nowNanos, milliseconds));
        trimValues(renderDurations, nowNanos);
    }

    public synchronized Snapshot snapshot(long nowNanos) {
        trimTimes(frameTimes, nowNanos);
        trimTimes(displayTimes, nowNanos);
        trimValues(rawCaptureDurations, nowNanos);
        trimValues(captureDurations, nowNanos);
        trimValues(processingDurations, nowNanos);
        trimValues(previewLoopDurations, nowNanos);
        trimValues(renderDurations, nowNanos);

        IntervalStats frameIntervals = intervalStats(frameTimes);
        ValueStats rawCapture = valueStats(rawCaptureDurations);
        ValueStats capture = valueStats(captureDurations);
        ValueStats processing = valueStats(processingDurations);
        ValueStats previewLoop = valueStats(previewLoopDurations);
        ValueStats render = valueStats(renderDurations);
        return new Snapshot(
                fps(frameTimes),
                fps(displayTimes),
                frameIntervals.average,
                frameIntervals.jitter,
                frameIntervals.maximum,
                rawCapture.average,
                capture.average,
                capture.minimum,
                capture.maximum,
                processing.average,
                previewLoop.average,
                render.average,
                receivedFrames,
                displayedFrames,
                droppedFrames,
                duplicateFrames,
                Math.max(0, nowNanos - startedNanos) / 1_000_000L);
    }

    private void trimTimes(Deque<Long> values, long nowNanos) {
        long cutoff = nowNanos - windowNanos;
        while (!values.isEmpty() && values.peekFirst() < cutoff) {
            values.removeFirst();
        }
    }

    private void trimValues(Deque<TimedValue> values, long nowNanos) {
        long cutoff = nowNanos - windowNanos;
        while (!values.isEmpty() && values.peekFirst().timeNanos < cutoff) {
            values.removeFirst();
        }
    }

    private static double fps(Deque<Long> times) {
        if (times.size() < 2) {
            return 0;
        }
        long elapsed = times.peekLast() - times.peekFirst();
        return elapsed <= 0 ? 0 : (times.size() - 1) * 1_000_000_000.0 / elapsed;
    }

    private static IntervalStats intervalStats(Deque<Long> times) {
        if (times.size() < 2) {
            return new IntervalStats();
        }
        long previous = -1;
        int count = 0;
        double sum = 0;
        double sumSquares = 0;
        double maximum = 0;
        for (long time : times) {
            if (previous >= 0) {
                double milliseconds = (time - previous) / 1_000_000.0;
                count++;
                sum += milliseconds;
                sumSquares += milliseconds * milliseconds;
                maximum = Math.max(maximum, milliseconds);
            }
            previous = time;
        }
        double average = count == 0 ? 0 : sum / count;
        double variance = count == 0 ? 0 : Math.max(0, sumSquares / count - average * average);
        return new IntervalStats(average, Math.sqrt(variance), maximum);
    }

    private static ValueStats valueStats(Deque<TimedValue> values) {
        if (values.isEmpty()) {
            return new ValueStats();
        }
        double sum = 0;
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = 0;
        for (TimedValue value : values) {
            sum += value.value;
            minimum = Math.min(minimum, value.value);
            maximum = Math.max(maximum, value.value);
        }
        return new ValueStats(sum / values.size(), minimum, maximum);
    }

    static long fingerprint(BufferedImage image) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, image.getWidth());
        hash = mix(hash, image.getHeight());
        int columns = Math.min(8, Math.max(1, image.getWidth()));
        int rows = Math.min(8, Math.max(1, image.getHeight()));
        for (int row = 0; row < rows; row++) {
            int y = rows == 1 ? 0 : row * (image.getHeight() - 1) / (rows - 1);
            for (int column = 0; column < columns; column++) {
                int x = columns == 1 ? 0
                        : column * (image.getWidth() - 1) / (columns - 1);
                hash = mix(hash, image.getRGB(x, y));
            }
        }
        return hash;
    }

    private static long mix(long hash, int value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static class TimedValue {
        final long timeNanos;
        final double value;

        TimedValue(long timeNanos, double value) {
            this.timeNanos = timeNanos;
            this.value = value;
        }
    }

    private static class IntervalStats {
        final double average;
        final double jitter;
        final double maximum;

        IntervalStats() {
            this(0, 0, 0);
        }

        IntervalStats(double average, double jitter, double maximum) {
            this.average = average;
            this.jitter = jitter;
            this.maximum = maximum;
        }
    }

    private static class ValueStats {
        final double average;
        final double minimum;
        final double maximum;

        ValueStats() {
            this(0, 0, 0);
        }

        ValueStats(double average, double minimum, double maximum) {
            this.average = average;
            this.minimum = minimum;
            this.maximum = maximum;
        }
    }

    public static class Snapshot {
        public final double captureFps;
        public final double displayFps;
        public final double frameIntervalAverageMs;
        public final double frameIntervalJitterMs;
        public final double maximumStallMs;
        public final double rawCaptureAverageMs;
        public final double captureAverageMs;
        public final double captureMinimumMs;
        public final double captureMaximumMs;
        public final double processingAverageMs;
        public final double previewLoopAverageMs;
        public final double renderAverageMs;
        public final long receivedFrames;
        public final long displayedFrames;
        public final long droppedFrames;
        public final long duplicateFrames;
        public final long elapsedMillis;

        Snapshot(double captureFps, double displayFps, double frameIntervalAverageMs,
                double frameIntervalJitterMs, double maximumStallMs,
                double rawCaptureAverageMs, double captureAverageMs,
                double captureMinimumMs, double captureMaximumMs, double processingAverageMs,
                double previewLoopAverageMs, double renderAverageMs,
                long receivedFrames, long displayedFrames,
                long droppedFrames,
                long duplicateFrames, long elapsedMillis) {
            this.captureFps = captureFps;
            this.displayFps = displayFps;
            this.frameIntervalAverageMs = frameIntervalAverageMs;
            this.frameIntervalJitterMs = frameIntervalJitterMs;
            this.maximumStallMs = maximumStallMs;
            this.rawCaptureAverageMs = rawCaptureAverageMs;
            this.captureAverageMs = captureAverageMs;
            this.captureMinimumMs = captureMinimumMs;
            this.captureMaximumMs = captureMaximumMs;
            this.processingAverageMs = processingAverageMs;
            this.previewLoopAverageMs = previewLoopAverageMs;
            this.renderAverageMs = renderAverageMs;
            this.receivedFrames = receivedFrames;
            this.displayedFrames = displayedFrames;
            this.droppedFrames = droppedFrames;
            this.duplicateFrames = duplicateFrames;
            this.elapsedMillis = elapsedMillis;
        }
    }
}
