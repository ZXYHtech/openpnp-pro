package org.openpnp.stencil;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import com.google.gson.*;

/**
 * Serialized, cancellable CAD jobs. Persistent states survive dialog close and restart.
 */
public final class StencilJobs implements AutoCloseable {

    public static final class State {

        public String id, kind, model, status, message, directory;

        public long startedAt, finishedAt;

        public int progress;

        public JsonObject parameters;

        public Map<String, String> files = new LinkedHashMap<>();

        @Override
        public String toString() {
            return (kind.equals("fixture") ? "夹具" : "转轴测试") + " · " + model + " · " + message;
        }

        public long elapsedSeconds() {
            return Math.max(0, ((finishedAt == 0 ? System.currentTimeMillis() : finishedAt) - startedAt) / 1000);
        }

        public long remainingSeconds() {
            return progress < 8 ? -1 : Math.max(0, Math.round(elapsedSeconds() * (100.0 - progress) / Math.max(1, progress)));
        }
    }

    private static final Map<Path, StencilJobs> SHARED = new HashMap<>();

    public static synchronized StencilJobs shared(Path root) throws IOException {
        Path path = root.toAbsolutePath().normalize();
        StencilJobs service = SHARED.get(path);
        if (service == null) {
            service = new StencilJobs(path);
            SHARED.put(path, service);
        }
        return service;
    }

    private final Path root;

    private final List<State> jobs = new ArrayList<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Stencil-CAD");
        t.setDaemon(true);
        return t;
    });

    private State active;

    private Process process;

    private AtomicBoolean canceled = new AtomicBoolean();

    private boolean closed;

    public StencilJobs(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Path history = root.resolve("jobs");
        Files.createDirectories(history);
        try (java.util.stream.Stream<Path> dirs = Files.list(history)) {
            for (Path file : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                Path state = file.resolve("state.json");
                if (!Files.exists(state)) {
                    continue;
                }
                try {
                    State s = StencilFiles.JSON.fromJson(StencilFiles.text(state), State.class);
                    if (s == null || s.id == null || s.parameters == null || !file.getFileName().toString().equals(s.id)) {
                        continue;
                    }
                    if (!Paths.get(s.directory).toAbsolutePath().normalize().startsWith(root.resolve("output"))) {
                        continue;
                    }
                    if (s.status.equals("running") || s.status.equals("queued")) {
                        s.status = "interrupted";
                        s.message = "上次退出时任务中断，可重新生成";
                        s.finishedAt = System.currentTimeMillis();
                        StencilFiles.write(state, s);
                    }
                    jobs.add(s);
                } catch (Exception corrupt) {
                    /* One corrupt job must not hide the other saved results. */
                }
            }
        }
        jobs.sort((a, b) -> Long.compare(b.startedAt, a.startedAt));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                close();
            } catch (Exception ignored) {
            }
        }, "Stencil-CAD-shutdown"));
    }

    public synchronized List<State> history() {
        List<State> r = new ArrayList<>();
        for (State s : jobs) {
            r.add(copy(s));
        }
        return r;
    }

    public synchronized State get(String id) {
        for (State s : jobs) {
            if (s.id.equals(id)) {
                return copy(s);
            }
        }
        return null;
    }

    public synchronized boolean busy() {
        return active != null;
    }

    private State copy(State s) {
        return StencilFiles.JSON.fromJson(StencilFiles.JSON.toJson(s), State.class);
    }

    private Path statePath(State s) {
        return root.resolve("jobs").resolve(s.id).resolve("state.json");
    }

    public synchronized State start(StencilParameters p, boolean coupons) throws IOException {
        if (closed) {
            throw new IOException("生成服务已关闭");
        }
        if (active != null) {
            throw new IOException("已有模型正在生成，请等待完成或取消");
        }
        State s = new State();
        s.id = UUID.randomUUID().toString();
        s.kind = coupons ? "hinge_calibration" : "fixture";
        s.model = p.s("model");
        s.parameters = p.json();
        s.status = "queued";
        s.message = "准备生成";
        s.startedAt = System.currentTimeMillis();
        Path dir = root.resolve("output").resolve(StencilFiles.safeName(s.model)).resolve(s.id);
        Files.createDirectories(dir);
        s.directory = dir.toString();
        StencilFiles.write(statePath(s), s);
        jobs.add(0, s);
        active = s;
        canceled = new AtomicBoolean();
        AtomicBoolean token = canceled;
        executor.submit(() -> run(s, p, token));
        return copy(s);
    }

    private synchronized void update(State s, int progress, String message) throws IOException {
        s.progress = progress;
        s.message = message;
        StencilFiles.write(statePath(s), s);
    }

    private void checkCanceled(AtomicBoolean token) throws InterruptedException {
        if (token.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("用户已取消");
        }
    }

    private void run(State s, StencilParameters p, AtomicBoolean token) {
        Path dir = Paths.get(s.directory);
        try {
            synchronized (this) {
                s.status = "running";
            }
            update(s, 2, "检查本地 CAD 内核");
            Path exe = StencilNative.prepare(root.resolve("cache"));
            checkCanceled(token);
            StencilFiles.write(dir.resolve("parameters.json"), p.envelope());
            update(s, 5, "计算结构与凹刻文字");
            JsonObject task = s.kind.equals("fixture") ? new StencilCadTask().fixture(p) : new StencilCadTask().coupons(p);
            StencilFiles.write(dir.resolve("cad-input.json"), task);
            checkCanceled(token);
            Process child = new ProcessBuilder(exe.toString()).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(dir.resolve("worker.log").toFile()).start();
            synchronized (this) {
                process = child;
            }
            long timeout = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);
            try {
                while (!child.waitFor(300, TimeUnit.MILLISECONDS)) {
                    checkCanceled(token);
                    if (System.nanoTime() > timeout) {
                        throw new IOException("CAD 生成超过 20 分钟，已停止；可缩小模型后重试");
                    }
                    int completed = 0;
                    for (JsonElement e : task.getAsJsonArray("exports")) {
                        if (Files.exists(dir.resolve(e.getAsJsonObject().get("name").getAsString() + ".step"))) {
                            completed++;
                        }
                    }
                    String log = StencilFiles.text(dir.resolve("worker.log"));
                    synchronized (this) {
                        for (JsonElement e : task.getAsJsonArray("exports")) {
                            String name = e.getAsJsonObject().get("name").getAsString();
                            if (log.contains("EXPORT " + name)) {
                                s.files.put(name + ".step", name + ".step");
                                if (e.getAsJsonObject().get("mesh").getAsBoolean()) {
                                    s.files.put(name + ".stl", name + ".stl");
                                }
                            }
                        }
                    }
                    int progress = Math.min(83, 8 + completed * 17 + (int) Math.min(18, s.elapsedSeconds() / 4));
                    if (progress != s.progress) {
                        update(s, progress, "生成实体、检查翻转干涉并验证 STEP（" + s.elapsedSeconds() + " 秒）");
                    }
                }
                checkCanceled(token);
                if (child.exitValue() != 0) {
                    String log = StencilFiles.text(dir.resolve("worker.log"));
                    String tail = log.substring(Math.max(0, log.length() - 1800));
                    throw new IOException("实体校验未通过：\n" + tail);
                }
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
                synchronized (this) {
                    process = null;
                }
            }
            update(s, 86, "生成打印网格与报告");
            JsonObject report = new JsonParser().parse(StencilFiles.text(dir.resolve("cad-report.json"))).getAsJsonObject();
            report.add("parameters", p.json());
            report.add("plan", new StencilPlan(p).json());
            report.addProperty("physical_test", false);
            report.addProperty("source_app", "stencil-studio 1.14.0");
            report.addProperty("implementation", "OpenPnP Pro Java / native OCCT, no Python");
            try (InputStream in = StencilJobs.class.getResourceAsStream("provenance.json")) {
                if (in != null) {
                    report.add("provenance", new JsonParser().parse(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)));
                }
            }
            if (s.kind.equals("fixture")) {
                double solid = report.getAsJsonObject("measurements").getAsJsonObject("solid_base").get("volume_mm3").getAsDouble(), actual = report.getAsJsonObject("parts").getAsJsonObject("pcb_base").get("volume_mm3").getAsDouble();
                JsonObject relief = new JsonObject();
                relief.addProperty("solid_base_volume_mm3", solid);
                relief.addProperty("removed_volume_mm3", solid - actual);
                relief.addProperty("reduction_percent", 100 * (solid - actual) / solid);
                relief.addProperty("remaining_pcb_floor_mm", new StencilPlan(p).pocketFloor - new StencilPlan(p).reliefDepth);
                report.add("weight_relief", relief);
            }
            List<String> publicFiles = new ArrayList<>();
            for (JsonElement item : task.getAsJsonArray("exports")) {
                checkCanceled(token);
                String name = item.getAsJsonObject().get("name").getAsString();
                publicFiles.add(name + ".step");
                if (item.getAsJsonObject().get("mesh").getAsBoolean()) {
                    StencilMesh mesh = StencilMesh.read(dir.resolve(name + ".mesh.json"));
                    mesh.write3mf(dir.resolve(name + ".3mf"));
                    publicFiles.add(name + ".stl");
                    publicFiles.add(name + ".3mf");
                }
            }
            StencilFiles.write(dir.resolve("report.json"), report);
            StencilFiles.writeText(dir.resolve("instructions.txt"), new StencilPlan(p).instructions());
            publicFiles.addAll(Arrays.asList("parameters.json", "report.json", "instructions.txt", "cad-input.json"));
            for (String name : publicFiles) {
                String target = StencilFiles.safeName(p.s("model")) + "_" + name;
                Files.copy(dir.resolve(name), dir.resolve(target));
                synchronized (this) {
                    s.files.put(name, target);
                }
            }
            update(s, 95, "打包模型与可复现的 CAD 指令");
            String bundle = StencilFiles.safeName(p.s("model")) + "_bundle.zip";
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(dir.resolve(bundle)))) {
                for (String name : publicFiles) {
                    checkCanceled(token);
                    String actual = s.files.get(name);
                    z.putNextEntry(new ZipEntry(actual));
                    Files.copy(dir.resolve(actual), z);
                    z.closeEntry();
                }
            }
            synchronized (this) {
                s.files.put("bundle.zip", bundle);
                s.status = "complete";
                s.finishedAt = System.currentTimeMillis();
                try {
                    update(s, 100, s.kind.equals("fixture") ? "已完成：三件夹具及装配 STEP，几何校验通过" : "已完成：九组补偿测试，18 件同板打印");
                } catch (IOException failure) {
                    s.status = "running";
                    throw failure;
                }
                if (active == s) {
                    active = null;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishFailure(s, "canceled", "已取消，原有结果保留");
        } catch (Throwable e) {
            finishFailure(s, "failed", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            synchronized (this) {
                if (active == s) {
                    active = null;
                }
                process = null;
            }
        }
    }

    private synchronized void finishFailure(State s, String status, String message) {
        s.status = status;
        s.finishedAt = System.currentTimeMillis();
        if (active == s) {
            active = null;
        }
        try {
            update(s, s.progress, message);
        } catch (IOException ignored) {
            s.message = message + "（任务状态保存失败）";
        }
    }

    public synchronized void cancel() {
        if (active != null) {
            canceled.set(true);
            if (process != null) {
                process.destroy();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancel();
        executor.shutdownNow();
    }

    public static Path file(State s, String name) throws IOException {
        String value = s.files.get(name);
        if (value == null) {
            throw new IOException("文件尚未生成");
        }
        Path dir = Paths.get(s.directory).toAbsolutePath().normalize(), file = dir.resolve(value).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            throw new IOException("生成文件不存在");
        }
        return file;
    }
}
