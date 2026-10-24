package org.openpnp.machine.reference.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.openpnp.machine.reference.camera.ReferenceCamera;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Camera;

/** One acquisition stream for both nozzles; crop before retaining frames to bound heap use. */
public final class ContinuousFlyingCapture {
    public static final class Frame {
        public final BufferedImage image;
        public final long received;
        public final double x, y;
        Frame(BufferedImage image, long received, double x, double y) {
            this.image = image;
            this.received = received;
            this.x = x;
            this.y = y;
        }
    }
    public static final class Result {
        public final List<Frame> first, second;
        public final double elapsedMs;
        public final int captured;
        Result(List<Frame> first, List<Frame> second, double ms, int captured) {
            this.first = first;
            this.second = second;
            this.elapsedMs = ms;
            this.captured = captured;
        }
    }

    public static Result capture(ContinuousFlyingTrajectory trajectory, Camera camera,
            FlyingVisionRegistration.Model firstReference, FlyingVisionRegistration.Model secondReference) throws Exception {
        if (!(camera instanceof ReferenceCamera) || camera.getWidth() < 384 || camera.getHeight() < 384) {
            throw new Exception("连续飞拍需要支持直接采集且不小于 384 像素的相机");
        }
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch ready = new CountDownLatch(1);
        List<Frame> captured = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        int capacity = (int)Math.min(160, Runtime.getRuntime().maxMemory() / 8 / (384 * 384 * 3));
        Thread worker = new Thread(() -> {
            Thread.currentThread().setContextClassLoader(Configuration.class.getClassLoader());
            try {
                while (running.get()) {
                    BufferedImage full = ((ReferenceCamera)camera).captureTransformed();
                    long time = System.nanoTime();
                    BufferedImage crop = new BufferedImage(384, 384, BufferedImage.TYPE_3BYTE_BGR);
                    var graphics = crop.createGraphics();
                    graphics.drawImage(full, 192 - full.getWidth()/2, 192 - full.getHeight()/2, null);
                    graphics.dispose();
                    if (captured.size() >= capacity) { throw new Exception("连续飞拍超过有界图像缓存"); }
                    captured.add(new Frame(crop, time, Double.NaN, Double.NaN));
                    ready.countDown();
                }
            } catch (Throwable problem) {
                failure.set(problem);
                ready.countDown();
            }
        }, "Continuous-dual-nozzle-capture");
        worker.setDaemon(true);
        long begun = System.nanoTime();
        camera.actuateLightBeforeCapture();
        try {
            worker.start();
            if (!ready.await(2000, TimeUnit.MILLISECONDS)) { throw new Exception("相机未及时返回图像"); }
            if (failure.get() != null) {
                throw new Exception("相机首帧采集失败，未开始连续运动", failure.get());
            }
            trajectory.execute();
        } finally {
            running.set(false);
            worker.join(1500);
            camera.actuateLightAfterCapture();
        }
        if (worker.isAlive() || failure.get() != null) {
            throw new Exception("连续飞拍采集异常", failure.get());
        }
        List<List<Frame>> first = groups(captured, firstReference, camera);
        List<List<Frame>> second = groups(captured, secondReference, camera);
        if (first.isEmpty() || second.isEmpty()) { throw new Exception("连续飞拍没有得到两头完整运动帧"); }
        List<Frame> a = first.get(0), b = second.get(second.size()-1);
        double separation = (b.get(0).received-a.get(a.size()-1).received)/1e9;
        if (separation < .15) { throw new Exception("连续飞拍图像不能可靠区分 N2 与 N1"); }
        return new Result(a, b, (System.nanoTime() - begun)/1e6, captured.size());
    }

    static List<List<Frame>> groups(List<Frame> input, FlyingVisionRegistration.Model model, Camera camera) {
        List<List<Frame>> result = new ArrayList<>();
        List<Frame> current = new ArrayList<>();
        Frame previous = null;
        for (Frame raw : input) {
            try {
                var registered = FlyingVisionRegistration.register(model, raw.image, camera);
                Frame frame = new Frame(registered.image, raw.received, registered.x, registered.y);
                if (previous != null && (frame.received-previous.received > 180_000_000L || frame.x < previous.x-.04)) {
                    retain(result, current);
                    current = new ArrayList<>();
                    previous = null;
                }
                if (previous == null || frame.x-previous.x >= .015) {
                    current.add(frame);
                    previous = frame;
                }
            } catch (Exception outsideWindow) { /* Expected between the two nozzles. */ }
        }
        retain(result, current);
        return result;
    }

    private static void retain(List<List<Frame>> result, List<Frame> group) {
        if (group.size() >= 3 && group.get(group.size()-1).x-group.get(0).x >= .10) { result.add(group); }
    }

    public static BufferedImage fullFrame(Frame frame, Camera camera) {
        BufferedImage result = new BufferedImage(camera.getWidth(), camera.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        var graphics = result.createGraphics();
        graphics.drawImage(frame.image, result.getWidth()/2-192, result.getHeight()/2-192, null);
        graphics.dispose();
        return result;
    }
}
