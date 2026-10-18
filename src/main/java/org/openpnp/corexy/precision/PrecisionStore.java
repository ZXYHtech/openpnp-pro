package org.openpnp.corexy.precision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonDeserializationContext;

/** Atomic profiles and raw CSV, with integrity checking and separate simulation reports. */
public final class PrecisionStore {
    private final Path root;
    private final Gson gson = new GsonBuilder().setPrettyPrinting()
            .registerTypeAdapter(PrecisionSample.class, new SampleJsonAdapter()).create();

    public PrecisionStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path getRoot() {
        return root;
    }

    public static final class Snapshot {
        private final java.util.Map<String, byte[]> files = new java.util.LinkedHashMap<>();
    }

    public Snapshot snapshot() throws IOException {
        Snapshot snapshot = new Snapshot();
        for (String name : new String[] {"profile.json", "profile.sha256", "enabled",
                "profile-previous.json", "profile-previous.sha256", "profile-previous.enabled"}) {
            Path file = root.resolve(name);
            snapshot.files.put(name, Files.isRegularFile(file) ? Files.readAllBytes(file) : null);
        }
        return snapshot;
    }

    public void restore(Snapshot snapshot) throws IOException {
        IOException failure = null;
        for (java.util.Map.Entry<String, byte[]> file : snapshot.files.entrySet()) {
            try {
                if (file.getValue() == null) {
                    Files.deleteIfExists(root.resolve(file.getKey()));
                }
                else {
                    atomicWriteBytes(root.resolve(file.getKey()), file.getValue());
                }
            }
            catch (IOException rejected) {
                if (failure == null) {
                    failure = rejected;
                }
                else {
                    failure.addSuppressed(rejected);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public Path saveReport(CalibrationController.Report report) throws IOException {
        Path directory = root.resolve(report.simulation ? "simulation" : "sessions")
                .resolve(report.createdAt + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(directory);
        JsonObject summary = new JsonObject();
        summary.addProperty("schema", report.schema);
        summary.addProperty("createdAt", report.createdAt);
        summary.addProperty("simulation", report.simulation);
        summary.addProperty("enabled", report.enabled);
        summary.addProperty("targetReached", report.targetReached);
        summary.addProperty("fingerprint", report.fingerprint);
        summary.addProperty("outcome", report.outcome);
        summary.addProperty("noise", report.noise);
        summary.addProperty("settlingMillis", report.settlingMillis);
        summary.add("request", gson.toJsonTree(report.request));
        summary.add("evaluation", gson.toJsonTree(report.evaluation));
        summary.add("chosen", gson.toJsonTree(report.chosen));
        summary.add("baselineFinal", gson.toJsonTree(report.baselineFinal));
        summary.add("correctedFinal", gson.toJsonTree(report.correctedFinal));
        summary.add("notes", gson.toJsonTree(report.notes));
        summary.add("previousMetrics", gson.toJsonTree(report.previousMetrics));
        atomicWrite(directory.resolve("report.json"), gson.toJson(summary));
        writeCsv(directory.resolve("training.csv"), report.training);
        writeCsv(directory.resolve("validation.csv"), report.validation);
        writeCsv(directory.resolve("final.csv"), report.finalObservations);
        writeCsv(directory.resolve("baseline-final.csv"), report.baselineObservations);
        writeCsv(directory.resolve("previous-profile-final.csv"), report.previousObservations);
        int candidate = 0;
        for (List<PrecisionSample> samples : report.finalAttempts) {
            writeCsv(directory.resolve("candidate-final-" + (++candidate) + ".csv"), samples);
        }
        return directory;
    }

    /** Records only the final persistence outcome; acquisition evidence is never rewritten. */
    public void updateReportCommit(Path directory, boolean enabled, String outcome) throws IOException {
        if (directory == null || outcome == null) {
            throw new IllegalArgumentException("校准提交状态必须指定记录目录和结果说明。");
        }
        Path normalized = directory.toAbsolutePath().normalize();
        boolean simulation = root.resolve("simulation").equals(normalized.getParent())
                && !"verifications".equals(normalized.getFileName().toString());
        if (!root.resolve("sessions").equals(normalized.getParent()) && !simulation) {
            throw new IllegalArgumentException("只允许更新当前数据目录中的校准记录。");
        }
        Path realRoot = root.toRealPath();
        Path realDirectory = normalized.toRealPath();
        Path file = normalized.resolve("report.json");
        if (!realDirectory.startsWith(realRoot) || Files.isSymbolicLink(file)
                || !file.toRealPath().startsWith(realRoot)) {
            throw new IllegalArgumentException("校准记录真实路径超出当前数据目录。");
        }
        if (simulation && enabled) {
            throw new IllegalArgumentException("模拟校准不能记录为已启用生产补偿。");
        }
        try {
            JsonObject summary = new com.google.gson.JsonParser().parse(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!summary.has("schema") || summary.get("schema").getAsDouble() != 1
                    || !summary.has("simulation") || !summary.get("simulation").isJsonPrimitive()
                    || !summary.get("simulation").getAsJsonPrimitive().isBoolean()
                    || summary.get("simulation").getAsBoolean() != simulation
                    || !summary.has("createdAt") || !Double.isFinite(summary.get("createdAt").getAsDouble())
                    || summary.get("createdAt").getAsDouble() <= 0
                    || !summary.has("request") || !summary.get("request").isJsonObject()) {
                throw new IllegalArgumentException("校准报告结构无效。");
            }
            if (enabled) {
                for (String raw : new String[] {"training.csv", "validation.csv", "final.csv", "baseline-final.csv"}) {
                    if (!Files.isRegularFile(normalized.resolve(raw))) {
                        throw new IOException("校准原始数据未完整保存，不能记录为已启用：" + raw);
                    }
                }
            }
            summary.addProperty("enabled", enabled);
            summary.addProperty("outcome", outcome);
            atomicWrite(file, gson.toJson(summary));
        }
        catch (RuntimeException invalid) {
            throw new IOException("校准提交状态无法更新，原始报告已保留。", invalid);
        }
    }

    public void saveProfile(PrecisionProfile profile, boolean enable) throws Exception {
        if (profile.solutionId == null || profile.solutionId.isEmpty()) {
            profile.solutionId = java.util.UUID.randomUUID().toString();
        }
        profile.validate(profile.fingerprint);
        if (profile.fingerprint.startsWith("SIMULATION")) {
            throw new IllegalArgumentException("模拟档案不能作为生产补偿。");
        }
        Files.createDirectories(root);
        // Keep every measured solution even after later calibrations replace the active pointer.
        Path solution = root.resolve("solutions").resolve(profile.solutionId);
        byte[] bytes = gson.toJson(profile).getBytes(StandardCharsets.UTF_8);
        if (Files.exists(solution) && (!Files.isRegularFile(solution.resolve("profile.json"))
                || !Files.isRegularFile(solution.resolve("profile.sha256"))
                || !java.util.Arrays.equals(bytes, Files.readAllBytes(solution.resolve("profile.json")))
                || !digest(bytes).equals(new String(Files.readAllBytes(solution.resolve("profile.sha256")),
                        StandardCharsets.UTF_8).trim()))) {
            // Re-saving a changed object is a new solution, never an edit of earlier evidence.
            profile.solutionId = java.util.UUID.randomUUID().toString();
            solution = root.resolve("solutions").resolve(profile.solutionId);
            bytes = gson.toJson(profile).getBytes(StandardCharsets.UTF_8);
        }
        if (!Files.isRegularFile(solution.resolve("profile.json"))) {
            atomicWrite(solution.resolve("profile.sha256"), digest(bytes));
            atomicWrite(solution.resolve("profile.json"), new String(bytes, StandardCharsets.UTF_8));
        }
        if (Files.isRegularFile(root.resolve("profile.json"))) {
            Files.copy(root.resolve("profile.json"), root.resolve("profile-previous.json"), StandardCopyOption.REPLACE_EXISTING);
            for (String name : new String[] {"sha256", "enabled"}) {
                Path source = root.resolve(name.equals("enabled") ? name : "profile." + name);
                if (Files.isRegularFile(source)) {
                    Files.copy(source, root.resolve("profile-previous." + name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        atomicWrite(root.resolve("profile.json"), new String(bytes, StandardCharsets.UTF_8));
        atomicWrite(root.resolve("profile.sha256"), digest(bytes));
        setEnabled(enable);
    }

    /** Setup is retained before calibration, so recording five points can span software launches. */
    public void saveFixedMarkers(FixedMarkerSetup setup) throws IOException {
        setup.validate();
        atomicWrite(root.resolve("fixed-markers.json"), gson.toJson(setup));
    }

    public FixedMarkerSetup loadFixedMarkers() throws IOException {
        Path file = root.resolve("fixed-markers.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            FixedMarkerSetup setup = gson.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                    FixedMarkerSetup.class);
            if (setup == null) {
                throw new IllegalArgumentException("空的固定 marker 设置。");
            }
            setup.validate();
            return setup;
        }
        catch (RuntimeException invalid) {
            throw new IOException("固定 marker 设置无法读取；请重新检查 5 个点。", invalid);
        }
    }

    /** JSON completion marker is written last; partial acquisition still records its raw evidence. */
    public Path saveVerification(StartupVerificationReport report) throws IOException {
        if (report.completedAt == 0) {
            report.completedAt = Math.max(System.currentTimeMillis(), report.createdAt);
        }
        report.validate();
        if (report.fingerprint.startsWith("SIMULATION") && !report.simulation) {
            throw new IllegalArgumentException("模拟校验不能保存为生产校验。");
        }
        Path directory = (report.simulation ? root.resolve("simulation") : root)
                .resolve("verifications")
                .resolve(report.createdAt + "-" + java.util.UUID.randomUUID().toString());
        writeCsv(directory.resolve("markers.csv"), report.samples);
        writeCsv(directory.resolve("home.csv"), report.homeSamples);
        atomicWrite(directory.resolve("report.json"), gson.toJson(report));
        return directory;
    }

    public StartupVerificationReport loadVerification(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("校验历史必须位于当前补偿数据目录。");
        }
        try {
            StartupVerificationReport report = gson.fromJson(
                    new String(Files.readAllBytes(normalized.resolve("report.json")), StandardCharsets.UTF_8),
                    StartupVerificationReport.class);
            if (report == null) {
                throw new IllegalArgumentException("空的校验记录。");
            }
            report.validate();
            return report;
        }
        catch (RuntimeException invalid) {
            throw new IOException("校验历史数据无效。", invalid);
        }
    }

    public List<Path> listVerificationDirectories() throws IOException {
        return listCompleteDirectories(root.resolve("verifications"), "report.json");
    }

    public List<Path> listSolutionDirectories() throws IOException {
        return listCompleteDirectories(root.resolve("solutions"), "profile.json");
    }

    /** Reviewable local evidence; never fits a model or changes the enabled flag. */
    public Path writeHistoryHtml() throws IOException {
        return PrecisionHistoryHtml.write(this, gson);
    }

    private List<Path> listCompleteDirectories(Path parent, String completionFile) throws IOException {
        List<Path> result = new java.util.ArrayList<>();
        if (Files.isDirectory(parent)) {
            try (java.nio.file.DirectoryStream<Path> directories = Files.newDirectoryStream(parent)) {
                for (Path directory : directories) {
                    if (Files.isDirectory(directory) && Files.isRegularFile(directory.resolve(completionFile))) {
                        result.add(directory);
                    }
                }
            }
        }
        result.sort(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        return java.util.Collections.unmodifiableList(result);
    }

    public PrecisionProfile loadProfile(String fingerprint) throws Exception {
        PrecisionProfile profile = loadForRestore();
        if (profile != null) {
            profile.validate(fingerprint);
        }
        return profile;
    }

    public PrecisionProfile loadProfile(OpenPnpCalibrationMachine backend) throws Exception {
        PrecisionProfile profile = loadForRestore();
        if (profile != null) {
            backend.validateProfile(profile);
            if (profile.fingerprintVersion == 1) {
                // Validation above proves the old identity before excluding volatile telemetry.
                Snapshot previous = snapshot();
                profile.fingerprint = backend.fingerprint();
                profile.fingerprintVersion = 2;
                try { saveProfile(profile, isEnabled()); }
                catch (Exception failure) {
                    try { restore(previous); }
                    catch (Exception rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            }
        }
        return profile;
    }

    /** Still verifies integrity, but allows old axis/camera settings to be restored after a change. */
    public PrecisionProfile loadForRestore() throws Exception {
        Path file = root.resolve("profile.json"), checksum = root.resolve("profile.sha256");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(checksum)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        if (!digest(bytes).equals(new String(Files.readAllBytes(checksum), StandardCharsets.UTF_8).trim())) {
            throw new IllegalStateException("补偿档案校验失败，已阻止启用。");
        }
        PrecisionProfile profile = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), PrecisionProfile.class);
        if (profile == null) {
            throw new IllegalStateException("补偿档案为空。");
        }
        profile.validate(profile.fingerprint);
        return profile;
    }

    public boolean isEnabled() throws IOException {
        Path file = root.resolve("enabled");
        return Files.isRegularFile(file)
                && "true".equals(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim());
    }

    public void setEnabled(boolean enabled) throws IOException {
        Files.createDirectories(root);
        atomicWrite(root.resolve("enabled"), Boolean.toString(enabled));
    }

    public static String digest(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        }
        return result.toString();
    }

    public static void atomicWrite(Path target, String contents) throws IOException {
        atomicWriteBytes(target, contents.getBytes(StandardCharsets.UTF_8));
    }

    public static void atomicWriteBytes(Path target, byte[] contents) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".corexy-", ".tmp");
        try {
            Files.write(temporary, contents);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeCsv(Path file, List<PrecisionSample> samples) throws IOException {
        StringBuilder csv = new StringBuilder("path,tool,x_mm,y_mm,z_mm,angle_deg,speed,acceleration_mm_s2,temperature_C,elapsed_s,delta_A,delta_B,travel_A,travel_B,settle_ms,state_A,state_B,correction_X_mm,correction_Y_mm\n");
        for (PrecisionSample sample : samples) {
            csv.append(quoted(sample.path)).append(',').append(quoted(sample.tool));
            double[] values = {sample.x, sample.y, sample.z, sample.angle, sample.speed, sample.acceleration,
                    sample.temperature, sample.elapsed, sample.deltaA, sample.deltaB, sample.travelA,
                    sample.travelB, sample.settleMillis, sample.stateA, sample.stateB,
                    sample.correctionX, sample.correctionY};
            for (double value : values) {
                csv.append(',');
                if (Double.isFinite(value)) {
                    csv.append(Double.toString(value));
                }
            }
            csv.append('\n');
        }
        atomicWrite(file, csv.toString());
    }

    private String quoted(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** Unknown temperatures are JSON null, preserving valid JSON and NaN on a later read. */
    private static final class SampleJsonAdapter
            implements JsonSerializer<PrecisionSample>, JsonDeserializer<PrecisionSample> {
        @Override
        public JsonElement serialize(PrecisionSample sample, java.lang.reflect.Type type,
                JsonSerializationContext context) {
            JsonObject value = new JsonObject();
            value.addProperty("path", sample.path);
            value.addProperty("tool", sample.tool);
            String[] names = {"x", "y", "z", "angle", "speed", "acceleration", "temperature", "elapsed",
                    "deltaA", "deltaB", "travelA", "travelB", "settleMillis", "stateA", "stateB",
                    "correctionX", "correctionY"};
            double[] values = {sample.x, sample.y, sample.z, sample.angle, sample.speed,
                    sample.acceleration, sample.temperature, sample.elapsed, sample.deltaA,
                    sample.deltaB, sample.travelA, sample.travelB, sample.settleMillis,
                    sample.stateA, sample.stateB, sample.correctionX, sample.correctionY};
            for (int index = 0; index < names.length; index++) {
                if (Double.isFinite(values[index])) {
                    value.addProperty(names[index], values[index]);
                }
                else {
                    value.add(names[index], com.google.gson.JsonNull.INSTANCE);
                }
            }
            value.addProperty("stateA", sample.stateA);
            value.addProperty("stateB", sample.stateB);
            return value;
        }

        @Override
        public PrecisionSample deserialize(JsonElement json, java.lang.reflect.Type type,
                JsonDeserializationContext context) {
            JsonObject value = json.getAsJsonObject();
            JsonElement temperature = value.get("temperature");
            return new PrecisionSample(value.get("path").getAsString(), value.get("tool").getAsString(),
                    number(value, "x"), number(value, "y"), number(value, "z"), number(value, "angle"),
                    number(value, "speed"), number(value, "acceleration"),
                    temperature == null || temperature.isJsonNull() ? Double.NaN : temperature.getAsDouble(),
                    number(value, "elapsed"), number(value, "deltaA"), number(value, "deltaB"),
                    number(value, "travelA"), number(value, "travelB"), number(value, "settleMillis"),
                    state(value, "stateA"), state(value, "stateB"),
                    number(value, "correctionX"), number(value, "correctionY"));
        }

        private static double number(JsonObject value, String name) {
            return value.get(name).getAsDouble();
        }

        private static int state(JsonObject value, String name) {
            double number = number(value, name);
            if (!Double.isFinite(number) || number != Math.rint(number) || Math.abs(number) > 1) {
                throw new IllegalArgumentException("无效方向状态。");
            }
            return (int) number;
        }
    }
}
