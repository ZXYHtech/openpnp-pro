package org.openpnp.corexy.precision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Standalone Chinese history page with local links and an auditable error list. */
final class PrecisionHistoryHtml {
    private final PrecisionStore store;
    private final Gson gson;
    private final Path root;
    private final List<StartupVerificationReport> fullChecks = new ArrayList<>();
    private int corruptRecords;

    private PrecisionHistoryHtml(PrecisionStore store, Gson gson) {
        this.store = store;
        this.gson = gson;
        root = store.getRoot();
    }

    static Path write(PrecisionStore store, Gson gson) throws IOException {
        return new PrecisionHistoryHtml(store, gson).write();
    }

    private Path write() throws IOException {
        Files.createDirectories(root);
        String calibrationRows = calibrationRows(false) + calibrationRows(true);
        String solutionRows = solutionRows();
        String verificationRows = verificationRows(false) + verificationRows(true);
        fullChecks.sort(java.util.Comparator.comparingLong(report -> report.createdAt));
        int passes = 0;
        for (StartupVerificationReport report : fullChecks) {
            if (report.passed) {
                passes++;
            }
        }
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:;\">")
                .append("<title>CoreXY 固定 marker 校准与校验历史</title><style>")
                .append("body{font:15px system-ui,'Microsoft YaHei',sans-serif;margin:0;background:#f3f6fb;color:#1b293d}")
                .append("main{max-width:1460px;margin:auto;padding:28px}h1{font-size:29px}h2{font-size:21px;margin-top:30px}")
                .append("p{line-height:1.7}.card,.table{background:white;border:1px solid #dce4ef;border-radius:12px;padding:18px;margin:14px 0}")
                .append(".table{overflow:auto;padding:0}table{border-collapse:collapse;width:100%;white-space:nowrap;font-size:13px}")
                .append("th,td{padding:12px;text-align:left;border-bottom:1px solid #e6ebf2}th{background:#eaf0f8}")
                .append("td.note{white-space:normal;min-width:240px;max-width:400px}a{color:#165dad;text-decoration:none}a:hover{text-decoration:underline}")
                .append(".ok{color:#16743e}.bad{color:#b32932}.muted{color:#637287}.badge{background:#eef3fb;padding:3px 7px;border-radius:5px}")
                .append(".id{font-family:monospace;font-size:11px;white-space:normal;word-break:break-all;max-width:220px}")
                .append("svg{width:100%;height:auto;max-height:300px}code{word-break:break-all}footer{color:#637287;font-size:12px}")
                .append("</style></head><body><main><h1>固定 marker 校准与校验历史</h1>")
                .append("<p>每次校准保留原始测量、评估结果和补偿方案。每次软件启动的 5 点校验、手动校验及 Home 视觉归零分别留档，按会话与方案编号关联。所有数值使用 mm。</p>")
                .append("<div class=\"card\"><strong>正式完整校验：").append(fullChecks.size())
                .append(" 次，通过 ").append(passes).append(" 次</strong><p>")
                .append(stabilitySummary()).append("</p>");
        if (corruptRecords > 0) {
            html.append("<p class=\"bad\">").append(corruptRecords)
                    .append(" 条记录损坏或未完整保存，已在对应表中显示路径和原因，未纳入统计。</p>");
        }
        html.append("<p class=\"muted\">历史趋势用于识别漂移与选择重新校准时机。系统不会用校验数据盲目改写已有补偿方案；新方案仍需独立评估后启用。Home 单点数据与模拟数据不计入正式完整 5 点校验统计。</p></div>")
                .append(trend()).append("<h2>校准记录</h2><div class=\"table\"><table><thead><tr>")
                .append("<th>时间 / 类型</th><th>结果</th><th>目标 / 噪声</th><th>补偿前 → 后 P95</th><th>校准原始资料</th>")
                .append("</tr></thead><tbody>").append(orEmpty(calibrationRows, 5)).append("</tbody></table></div>")
                .append("<h2>历次补偿方案</h2><div class=\"table\"><table><thead><tr>")
                .append("<th>时间</th><th>方案编号</th><th>补偿层</th><th>目标 / P95 / RMS / 最大偏差</th><th>方案与校准链接</th>")
                .append("</tr></thead><tbody>").append(orEmpty(solutionRows, 5)).append("</tbody></table></div>")
                .append("<h2>启动、手动与 Home 校验</h2><div class=\"table\"><table><thead><tr>")
                .append("<th>时间 / 类型</th><th>结果</th><th>会话 / 方案</th><th>Home 原始 X / Y</th><th>Home 残差 前 → 后</th>")
                .append("<th>目标 / P95 / RMS / 最大偏差</th><th>说明与原始数据</th></tr></thead><tbody>")
                .append(orEmpty(verificationRows, 7)).append("</tbody></table></div>")
                .append("<footer>生成时间：").append(escape(time(System.currentTimeMillis())))
                .append(" · 数据目录：<code>").append(escape(root.toString())).append("</code></footer></main></body></html>");
        Path page = root.resolve("history.html");
        PrecisionStore.atomicWrite(page, html.toString());
        return page;
    }

    private String calibrationRows(boolean simulation) throws IOException {
        StringBuilder rows = new StringBuilder();
        Path parent = root.resolve(simulation ? "simulation" : "sessions");
        for (Path directory : directories(parent)) {
            if (simulation && directory.getFileName().toString().equals("verifications")) {
                continue;
            }
            int rowStart = rows.length();
            try {
                JsonObject report = readObject(directory.resolve("report.json"));
                if (number(report, "schema").doubleValue() != 1
                        || number(report, "createdAt").longValue() <= 0
                        || !report.has("simulation") || !report.get("simulation").isJsonPrimitive()
                        || !report.get("simulation").getAsJsonPrimitive().isBoolean()
                        || report.get("simulation").getAsBoolean() != simulation
                        || !report.has("enabled") || !report.get("enabled").isJsonPrimitive()
                        || !report.get("enabled").getAsJsonPrimitive().isBoolean()
                        || (simulation && report.get("enabled").getAsBoolean())
                        || number(report, "noise").doubleValue() < 0) {
                    throw new IllegalArgumentException("校准报告版本、类型或提交状态无效。");
                }
                for (String raw : new String[] {"training.csv", "validation.csv", "final.csv", "baseline-final.csv"}) {
                    if (!Files.isRegularFile(directory.resolve(raw))) {
                        throw new IOException("缺少 " + raw);
                    }
                }
                rows.append("<tr><td>").append(escape(time(number(report, "createdAt").longValue())))
                        .append("<br><span class=\"badge\">").append(simulation ? "模拟校准" : "正式校准")
                        .append("</span></td><td class=\"note\">").append(escape(string(report, "outcome")))
                        .append("<br><span class=\"badge\">")
                        .append(report.get("enabled").getAsBoolean() ? "本次已提交启用" : "本次未启用")
                        .append("</span>")
                        .append("</td><td>").append(requestTarget(report)).append(" / ")
                        .append(mm(number(report, "noise").doubleValue())).append("</td><td>")
                        .append(metric(report.get("baselineFinal"), "p95")).append(" → ")
                        .append(metric(report.get("correctedFinal"), "p95")).append("</td><td>")
                        .append(link(directory.resolve("report.json"), "评估 JSON")).append(" · ")
                        .append(link(directory.resolve("training.csv"), "训练 CSV")).append(" · ")
                        .append(link(directory.resolve("validation.csv"), "独立验证 CSV")).append(" · ")
                        .append(link(directory.resolve("final.csv"), "补偿后 CSV")).append("</td></tr>");
            }
            catch (Exception damaged) {
                rows.setLength(rowStart);
                rows.append(damaged(directory, damaged, 5));
            }
        }
        return rows.toString();
    }

    private String solutionRows() throws IOException {
        StringBuilder rows = new StringBuilder();
        for (Path directory : directories(root.resolve("solutions"))) {
            int rowStart = rows.length();
            try {
                byte[] bytes = Files.readAllBytes(directory.resolve("profile.json"));
                String checksum = new String(Files.readAllBytes(directory.resolve("profile.sha256")), StandardCharsets.UTF_8).trim();
                if (!PrecisionStore.digest(bytes).equals(checksum)) {
                    throw new IOException("方案 SHA-256 校验失败");
                }
                PrecisionProfile profile = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), PrecisionProfile.class);
                profile.validate(profile.fingerprint);
                rows.append("<tr><td>").append(escape(time(profile.createdAt))).append("</td><td class=\"id\">")
                        .append(escape(profile.solutionId)).append("</td><td class=\"note\">")
                        .append(escape(layerNames(profile.model.getLayers()))).append("</td><td>")
                        .append(mm(profile.target)).append(" / ").append(mm(profile.corrected.p95))
                        .append(" / ").append(mm(profile.corrected.rms)).append(" / ").append(mm(profile.corrected.maximum))
                        .append("</td><td>").append(link(directory.resolve("profile.json"), "方案 JSON"))
                        .append(" · ").append(link(directory.resolve("profile.sha256"), "SHA-256"));
                if (!profile.calibrationDirectory.isEmpty()) {
                    Path calibration = localPath(profile.calibrationDirectory);
                    if (!Files.isRegularFile(calibration.resolve("report.json"))) {
                        throw new IOException("对应的校准评估记录缺失：" + profile.calibrationDirectory);
                    }
                    rows.append("<br>").append(link(calibration.resolve("report.json"), "对应校准评估"));
                }
                rows.append("</td></tr>");
            }
            catch (Exception damaged) {
                rows.setLength(rowStart);
                rows.append(damaged(directory, damaged, 5));
            }
        }
        return rows.toString();
    }

    private String verificationRows(boolean simulation) throws IOException {
        StringBuilder rows = new StringBuilder();
        Path parent = (simulation ? root.resolve("simulation") : root).resolve("verifications");
        for (Path directory : directories(parent)) {
            int rowStart = rows.length();
            try {
                StartupVerificationReport report = store.loadVerification(directory);
                for (String raw : new String[] {"home.csv", "markers.csv"}) {
                    if (!Files.isRegularFile(directory.resolve(raw))) {
                        throw new IOException("缺少 " + raw);
                    }
                }
                if (!simulation && !report.simulation && !"home".equals(report.kind)
                        && !report.cancelled && report.samples.size() >= 5
                        && report.metrics != null && report.metrics.count >= 5) {
                    fullChecks.add(report);
                }
                rows.append("<tr><td>").append(escape(time(report.createdAt)))
                        .append("<br><span class=\"badge\">").append(kind(report.kind))
                        .append(simulation || report.simulation ? " · 模拟" : "").append("</span></td><td class=\"")
                        .append(report.passed ? "ok" : "bad").append("\">")
                        .append(report.passed ? "通过" : report.cancelled ? "已取消" : "未通过")
                        .append("</td><td class=\"id\">").append(escape(report.sessionId)).append("<br>")
                        .append(escape(report.profileId)).append("</td><td>").append(mm(report.rawHomeX))
                        .append(" / ").append(mm(report.rawHomeY)).append("</td><td>")
                        .append(mm(report.beforeHomeResidual)).append(" → ").append(mm(report.afterHomeResidual))
                        .append("<br><span class=\"muted\">迭代 ").append(report.homeIterations)
                        .append("；应用 X/Y ").append(mm(report.homeCorrectionX)).append(" / ")
                        .append(mm(report.homeCorrectionY)).append("</span></td><td>").append(mm(report.target));
                if (report.metrics != null) {
                    rows.append(" / ").append(mm(report.metrics.p95)).append(" / ").append(mm(report.metrics.rms))
                            .append(" / ").append(mm(report.metrics.maximum));
                }
                else {
                    rows.append(" / 未完成测量");
                }
                rows.append("</td><td class=\"note\">").append(escape(report.outcome)).append("<br>")
                        .append(escape(String.join("；", report.notes))).append("<br>")
                        .append(link(directory.resolve("report.json"), "原始 JSON")).append(" · ")
                        .append(link(directory.resolve("home.csv"), "Home CSV")).append(" · ")
                        .append(link(directory.resolve("markers.csv"), "marker CSV")).append("<br>")
                        .append("marker 路径 ").append(report.samples.size()).append("；Home 帧 ")
                        .append(report.homeSamples.size()).append("</td></tr>");
            }
            catch (Exception damaged) {
                rows.setLength(rowStart);
                rows.append(damaged(directory, damaged, 7));
            }
        }
        return rows.toString();
    }

    private String stabilitySummary() {
        if (fullChecks.isEmpty()) {
            return "尚无正式 5 点校验数据。完成首次校准并启用后，软件启动、连接机器和 Home 流程会生成校验记录。";
        }
        StartupVerificationReport latest = fullChecks.get(fullChecks.size() - 1);
        String text = "最近一次完整校验：" + escape(time(latest.createdAt)) + "，"
                + (latest.passed ? "通过" : latest.cancelled ? "取消" : "未通过") + "。";
        if (latest.metrics != null) {
            text += "P95 " + mm(latest.metrics.p95) + "，目标 " + mm(latest.target)
                    + "；Home 视觉归零后残差 " + mm(latest.afterHomeResidual) + "。";
        }
        List<StartupVerificationReport> measured = measuredChecks();
        measured.removeIf(report -> !report.profileId.equals(latest.profileId));
        if (measured.size() >= 2) {
            double mean = 0;
            for (StartupVerificationReport report : measured) {
                mean += report.metrics.p95 / measured.size();
            }
            double variance = 0;
            for (StartupVerificationReport report : measured) {
                variance += Math.pow(report.metrics.p95 - mean, 2) / measured.size();
            }
            text += "同方案 " + measured.size() + " 次完整测量的平均 P95 " + mm(mean)
                    + "、标准差 " + mm(Math.sqrt(variance)) + "。";
        }
        if (measured.size() >= 6) {
            double first = 0, last = 0, noise = 0;
            for (int index = 0; index < 3; index++) {
                first += measured.get(index).metrics.p95 / 3;
                StartupVerificationReport recent = measured.get(measured.size() - 1 - index);
                last += recent.metrics.p95 / 3;
                noise = Math.max(noise, recent.noise);
            }
            text += "同方案最早 3 次 / 最近 3 次平均 P95：" + mm(first) + " / " + mm(last) + "。";
            if (last - first > Math.max(noise * 2, first * .08)) {
                text += "偏差出现上升趋势，建议检查固定点、相机及机械状态，再运行完整校准与独立评估。";
            }
        }
        return text;
    }

    private String trend() {
        List<StartupVerificationReport> measured = measuredChecks();
        if (measured.isEmpty()) {
            return "";
        }
        int first = Math.max(0, measured.size() - 100);
        List<StartupVerificationReport> recent = measured.subList(first, measured.size());
        double ceiling = .001;
        for (StartupVerificationReport report : recent) {
            ceiling = Math.max(ceiling, Math.max(report.metrics.p95, report.target));
        }
        ceiling *= 1.15;
        StringBuilder p95 = new StringBuilder(), target = new StringBuilder(), dots = new StringBuilder();
        for (int index = 0; index < recent.size(); index++) {
            StartupVerificationReport report = recent.get(index);
            double x = recent.size() == 1 ? 430 : 70 + 740.0 * index / (recent.size() - 1);
            double y = 190 - report.metrics.p95 / ceiling * 150;
            p95.append(number(x)).append(',').append(number(y)).append(' ');
            target.append(number(x)).append(',').append(number(190 - report.target / ceiling * 150)).append(' ');
            dots.append("<circle cx=\"").append(number(x)).append("\" cy=\"").append(number(y))
                    .append("\" r=\"4\" fill=\"").append(report.passed ? "#1c73c8" : "#c53338")
                    .append("\"><title>").append(escape(time(report.createdAt))).append(" P95 ")
                    .append(mm(report.metrics.p95)).append(" mm</title></circle>");
        }
        return "<div class=\"card\"><strong>完整 5 点校验 P95 趋势（最近最多 100 次）</strong>"
                + "<p class=\"muted\">蓝线：P95；红色虚线：各次校验目标。红点表示校验未通过。悬停查看时间与数值。</p>"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 850 225\" role=\"img\" aria-label=\"完整校验 P95 与目标趋势\">"
                + "<path d=\"M70 30V190H820\" fill=\"none\" stroke=\"#adb9c9\"/>"
                + "<text x=\"12\" y=\"40\" fill=\"#637287\">" + mm(ceiling * 1000) + " μm</text>"
                + "<text x=\"42\" y=\"194\" fill=\"#637287\">0</text>"
                + "<text x=\"70\" y=\"218\" fill=\"#637287\">早</text><text x=\"800\" y=\"218\" fill=\"#637287\">近</text>"
                + "<polyline points=\"" + target + "\" fill=\"none\" stroke=\"#c53338\" stroke-dasharray=\"6 4\"/>"
                + "<polyline points=\"" + p95 + "\" fill=\"none\" stroke=\"#1c73c8\" stroke-width=\"2\"/>"
                + dots + "</svg></div>";
    }

    private List<StartupVerificationReport> measuredChecks() {
        List<StartupVerificationReport> measured = new ArrayList<>();
        for (StartupVerificationReport report : fullChecks) {
            if (!report.cancelled && report.metrics != null) {
                measured.add(report);
            }
        }
        return measured;
    }

    private String damaged(Path directory, Exception reason, int columns) {
        corruptRecords++;
        return "<tr><td class=\"bad note\" colspan=\"" + columns + "\">记录损坏或未完整保存：<code>"
                + escape(root.relativize(directory).toString()) + "</code>；" + escape(reason.toString())
                + "<br>" + link(directory.resolve("report.json"), "原始评估/校验 JSON")
                + " · " + link(directory.resolve("profile.json"), "原始方案 JSON") + "</td></tr>";
    }

    private List<Path> directories(Path parent) throws IOException {
        List<Path> directories = new ArrayList<>();
        if (Files.isDirectory(parent)) {
            try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
                for (Path directory : entries) {
                    if (Files.isDirectory(directory)) {
                        directories.add(directory);
                    }
                }
            }
        }
        directories.sort(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        Collections.reverse(directories);
        return directories;
    }

    private JsonObject readObject(Path path) throws IOException {
        return new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private Path localPath(String value) {
        Path path = Paths.get(value);
        path = (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("校准记录链接超出当前数据目录。");
        }
        return path;
    }

    private String link(Path path, String title) {
        String uri = root.toUri().relativize(path.toAbsolutePath().normalize().toUri()).toASCIIString();
        return "<a href=\"" + escape(uri) + "\">" + escape(title) + "</a>";
    }

    private static String requestTarget(JsonObject report) {
        JsonElement request = report.get("request");
        return request == null || request.isJsonNull() ? "—" : mm(number(request.getAsJsonObject(), "target").doubleValue());
    }

    private static String metric(JsonElement metrics, String field) {
        return metrics == null || metrics.isJsonNull() ? "—" : mm(number(metrics.getAsJsonObject(), field).doubleValue());
    }

    private static Number number(JsonObject value, String field) {
        JsonElement number = value.get(field);
        if (number == null || number.isJsonNull() || !Double.isFinite(number.getAsDouble())) {
            throw new IllegalArgumentException("缺少有效数值 " + field);
        }
        return number.getAsNumber();
    }

    private static String string(JsonObject value, String field) {
        JsonElement text = value.get(field);
        return text == null || text.isJsonNull() ? "未完成" : text.getAsString();
    }

    private static String kind(String kind) {
        return "home".equals(kind) ? "Home 单点归零" : "manual".equals(kind) ? "手动 5 点校验" : "启动 5 点校验";
    }

    private static String layerNames(List<PrecisionModel.Layer> layers) {
        String[] names = {"公共偏置", "固定换向", "连续换向记忆", "A/B 联合状态", "几何误差", "空间网格",
                "速度与加速度", "温漂", "工具点", "换向状态空间网格"};
        List<String> selected = new ArrayList<>();
        for (PrecisionModel.Layer layer : layers) {
            selected.add(names[layer.ordinal()]);
        }
        return String.join(" + ", selected);
    }

    private static String time(long millis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(millis));
    }

    private static String mm(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String orEmpty(String rows, int columns) {
        return rows.isEmpty() ? "<tr><td colspan=\"" + columns + "\" class=\"muted\">暂无记录</td></tr>" : rows;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
