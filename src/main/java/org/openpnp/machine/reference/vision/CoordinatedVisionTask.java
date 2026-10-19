package org.openpnp.machine.reference.vision;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Image-only worker. Motion and result application remain on the machine thread. */
public final class CoordinatedVisionTask<T> {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), runnable -> {
                Thread thread = new Thread(runnable, "Coordinated-bottom-vision");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Future<T> result;
    private final long submitted = System.nanoTime();

    public CoordinatedVisionTask(Callable<T> analysis) {
        result = WORKER.submit(analysis);
    }

    public boolean isDone() {
        return result.isDone();
    }

    public T await() throws Exception {
        try {
            long remaining = TimeUnit.SECONDS.toNanos(3) - (System.nanoTime() - submitted);
            return result.isDone() ? result.get() : result.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new Exception("底部视觉图像处理失败", cause);
        } catch (java.util.concurrent.TimeoutException timeout) {
            // The worker owns its pipeline until it exits. Never release Mats
            // concurrently with processing, even after timeout or job abort.
            throw new Exception("底部视觉图像处理超时，已停止贴装。", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }
}
