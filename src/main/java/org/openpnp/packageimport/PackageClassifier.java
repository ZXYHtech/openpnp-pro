package org.openpnp.packageimport;

import java.util.Locale;

import org.openpnp.model.Footprint;
import org.openpnp.model.Package;

public final class PackageClassifier {
    public static final class Result {
        public final String categoryId;
        public final double confidence;
        public final String reason;
        Result(String categoryId, double confidence, String reason) {
            this.categoryId = categoryId;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    private PackageClassifier() {}

    public static Result classify(Package pkg) {
        String id = pkg.getId() == null ? "" : pkg.getId();
        String sourceReference = pkg.getSourceReference() == null ? "" : pkg.getSourceReference();
        String text = (id + ' '
                + (pkg.getStandardName() == null ? "" : pkg.getStandardName()) + ' '
                + (pkg.getAliases() == null ? "" : pkg.getAliases()))
                        .toUpperCase(Locale.ROOT);
        Footprint fp = pkg.getFootprint();
        int pads = fp == null ? 0 : fp.getPads().size();
        Result named = classifyName(id.toUpperCase(Locale.ROOT), text, pads);
        if (named != null) { return named; }
        Result source = classifySourceReference(sourceReference);
        if (source != null) { return source; }
        if (pads == 2) { return new Result("passive", 0.48,
                "检测到两个焊盘，但名称不足以区分电阻、电容或电感"); }
        if (pads >= 4 && isGrid(fp)) { return new Result("ic.bga", 0.64,
                "焊盘呈二维阵列，建议归入 BGA/CSP，需人工确认"); }
        if (pads >= 8) { return new Result("ic", 0.42,
                "多引脚封装，名称不足以判断具体 IC 外形"); }
        return new Result(null, 0.0, pads == 0 ? "没有焊盘几何，保持未分类"
                : "名称和几何信息不足，保持未分类");
    }

    private static Result classifyName(String primary, String t, int pads) {
        if (has(t, "BGA", "CSP", "WLCSP")) { return r("ic.bga", .96, "名称包含 BGA/CSP"); }
        if (has(t, "QFN", "DFN", "LGA", "WSON", "SON-")) { return r("ic.qfn", .95, "名称包含 QFN/DFN/LGA/SON"); }
        if (has(t, "LQFP", "TQFP", "QFP")) { return r("ic.qfp", .95, "名称包含 QFP 系列"); }
        if (has(t, "TSSOP", "MSOP", "SSOP", "SOP", "SOIC", "ESOP")) { return r("ic.so", .94, "名称包含 SO/SOP 系列"); }
        if (has(t, "DIP")) { return r("ic.dip", .92, "名称包含 DIP"); }
        if (has(t, "POWERPAK", "DPAK", "TO252", "TO-252", "TO263", "TO-263", "DIRECTFET"))
            { return r("discrete.power", .94, "名称包含功率器件封装关键词"); }
        if (has(t, "SOT", "SC70")) { return r("discrete.sot", .92, "名称包含 SOT/SC70"); }
        if (has(t, "MOSFET", "TRANSISTOR", "IGBT")) { return r("discrete.power", .9, "名称包含晶体管/功率器件关键词"); }
        if (has(t, "OPTO", "OPTOCOUPLER", "PHOTODIODE", "PHOTOTRANSISTOR"))
            { return r("discrete.opto", .92, "名称包含光耦/光电器件关键词"); }
        if (has(t, "CONNECTOR", "CONN", "HEADER", "TYPE-C", "TYPEC", "USB", "JST",
                "FPC", "FFC", "IPEX", "CARD", "HDR", "WAFER", "SMA-KE", "排针", "排母")
                || primary.matches("^(?:KF|PH\\d)[A-Z0-9._-]*"))
            { return r("connector", .93, "名称包含连接器、排针或接口关键词"); }
        if (has(t, "CRYSTAL", "OSCILLATOR", "XTAL", "RESONATOR", "CSTCE", "CSTNE", "CSTSE", "OSC-"))
            { return r("crystal", .93, "名称包含晶振、谐振器或振荡器关键词"); }
        if (has(t, "DIODE", "LED", "SOD", "DO214", "DO-214", "MELF", "WS2812"))
            { return r("discrete.diode-led", .94, "名称包含二极管/LED 外形"); }
        if (isExact(primary, "SMA", "SMB", "SMC"))
            { return r("discrete.diode-led", .82, "名称是常见 SMA/SMB/SMC 二极管封装，建议复核"); }
        if (has(t, "FILTER", "COMMONMODE", "COMMON_MODE"))
            { return r("passive.filter", .91, "名称包含滤波器关键词"); }
        if (has(t, "TRANSFORMER")) { return r("passive.transformer", .94, "名称包含变压器关键词"); }
        if (has(t, "SWITCH", "SW-SMD", "SW-TH", "BUTTON", "KEY-", "ENCODER",
                "3303W", "LY-K6"))
            { return r("electromechanical.switch", .91, "名称包含开关、按键或编码器关键词"); }
        if (has(t, "RELAY")) { return r("electromechanical.relay", .94, "名称包含继电器关键词"); }
        if (has(t, "MOTOR", "VALVE")) { return r("electromechanical.motor", .9, "名称包含电机/阀门关键词"); }
        if (has(t, "BUZZER", "BEEPER")) { return r("electromechanical.buzzer", .93, "名称包含蜂鸣器关键词"); }
        if (has(t, "SENSOR", "MIC-SMD", "MICROPHONE")) { return r("sensor", .91, "名称包含传感器/麦克风关键词"); }
        if (has(t, "DISPLAY", "7SEGMENT", "7-SEGMENT")) { return r("display", .91, "名称包含显示器件关键词"); }
        if (has(t, "FUSE", "VARISTOR", "TVS")) { return r("protection", .9, "名称包含保护器件关键词"); }
        if (has(t, "ANTENNA", "RF_", "RF-", "GPS", "GSM", "WIFI")) { return r("rf", .88, "名称包含射频/天线关键词"); }
        if (has(t, "MODULE", "SHIELD")) { return r("module", .8, "名称包含模块/屏蔽罩关键词"); }
        if (has(t, "TESTPOINT", "TEST_POINT", "测试点")) { return r("mechanical.test-point", .96, "名称包含测试点关键词"); }
        if (has(t, "FIDUCIAL", "REFERENCE_BOTTOM", "MOUNTINGHOLE", "MOUNTING_HOLE",
                "TOOLING", "螺丝", "螺钉", "螺孔", "不可回收", "防静电标识")
                || primary.matches("^M\\d+(?:\\.\\d+)?$"))
            { return r("mechanical", .95, "名称包含机械/定位标记关键词"); }
        if (has(t, "TANTAL", "CP_ELEC", "ELECTROLYTIC", "ELECTRO-", "CASE-B"))
            { return r("passive.polarized-capacitor", .92, "名称包含极性电容或标准钽电容外壳关键词"); }
        if (has(t, "INDUCTOR", "FERRITE", "BEAD", "IND-SMD")
                || starts(primary.trim(), "IND", "IND_", "IND-"))
            { return r("passive.inductor", .9, "名称包含电感/磁珠关键词"); }
        if (has(t, "CAPACITOR") || starts(primary.trim(), "CAP_", "CAP-", "C_"))
            { return r("passive.capacitor", .86, "名称以电容关键词或 C_ 开头"); }
        if (has(t, "RESISTOR") || starts(primary.trim(), "RES_", "RES-", "R_"))
            { return r("passive.resistor", .86, "名称以电阻关键词或 R_ 开头"); }
        String compact = primary.replaceAll("[^A-Z0-9]", "");
        if (compact.matches("(?:R|RES)(?:ARRAY)?(?:0201|0402|0603|0805|1206|1210|2010|2512).*"))
            { return r("passive.resistor", .98, "封装名以 R/RES 加标准片式尺寸命名"); }
        if (compact.matches("(?:TTC|NTC|PTC)(?:0201|0402|0603|0805|1206|1210)?.*"))
            { return r("passive.resistor", .94, "封装名符合热敏电阻命名"); }
        if (compact.matches("C(?:0201|0402|0603|0805|1206|1210|1812|3216|3528).*"))
            { return r("passive.capacitor", .98, "封装名以 C 加标准片式尺寸命名"); }
        if (compact.matches("L(?:0201|0402|0420|0603|0630|0805|1206|1210|4020|5650).*"))
            { return r("passive.inductor", .97, "封装名以 L 加常见电感尺寸命名"); }
        if (compact.matches("(?:0201|0402|0603|0805|1206|1210)0(?:OHM)?"))
            { return r("passive.resistor", .9, "尺寸名后带 0，识别为零欧电阻封装"); }
        if (primary.matches("^R[-_]\\d+(?:[-_].*)?$"))
            { return r("passive.resistor", .86, "封装名使用 R-数字电阻命名"); }
        if (pads == 2 && t.matches(".*(?:^|[^0-9])(0201|0402|0603|0805|1206|1210|2512)(?:[^0-9]|$).*$"))
            { return r("passive", .55, "名称包含常见两焊盘片式尺寸，但电气类别不确定"); }
        return null;
    }

    private static Result classifySourceReference(String sourceReference) {
        String source = sourceReference == null ? ""
                : sourceReference.replace('\\', '/').toUpperCase(Locale.ROOT);
        int pretty = source.indexOf(".PRETTY");
        if (pretty < 0) { return null; }
        int slash = source.lastIndexOf('/', pretty);
        String library = source.substring(slash + 1, pretty);
        if (library.startsWith("RESISTOR")) { return source("passive.resistor", library); }
        if (library.startsWith("CAPACITOR_TANTALUM")) { return source("passive.polarized-capacitor", library); }
        if (library.startsWith("CAPACITOR")) { return source("passive.capacitor", library); }
        if (starts(library, "INDUCTOR", "FERRITE")) { return source("passive.inductor", library); }
        if (library.startsWith("FILTER")) { return source("passive.filter", library); }
        if (library.startsWith("TRANSFORMER")) { return source("passive.transformer", library); }
        if (starts(library, "CRYSTAL", "OSCILLATOR")) { return source("crystal", library); }
        if (starts(library, "DIODE", "LED")) { return source("discrete.diode-led", library); }
        if (library.startsWith("OPTODEVICE")) { return source("discrete.opto", library); }
        if (starts(library, "TRANSISTOR_POWER", "PACKAGE_DIRECTFET")) { return source("discrete.power", library); }
        if (library.startsWith("PACKAGE_TO_SOT")) { return source("discrete.sot", library); }
        if (starts(library, "PACKAGE_BGA", "PACKAGE_CSP")) { return source("ic.bga", library); }
        if (starts(library, "PACKAGE_DFN_QFN", "PACKAGE_LGA", "PACKAGE_SON")) { return source("ic.qfn", library); }
        if (library.startsWith("PACKAGE_QFP")) { return source("ic.qfp", library); }
        if (starts(library, "PACKAGE_SO", "PACKAGE_LCC")) { return source("ic.so", library); }
        if (library.startsWith("PACKAGE_DIP")) { return source("ic.dip", library); }
        if (library.startsWith("PACKAGE_")) { return source("ic", library); }
        if (starts(library, "CONNECTOR", "TERMINALBLOCK", "SOCKET", "BATTERY")) { return source("connector", library); }
        if (starts(library, "BUTTON_SWITCH", "JUMPER", "POTENTIOMETER", "ROTARY_ENCODER"))
            { return source("electromechanical.switch", library); }
        if (library.startsWith("RELAY")) { return source("electromechanical.relay", library); }
        if (starts(library, "MOTORS", "VALVE")) { return source("electromechanical.motor", library); }
        if (library.startsWith("BUZZER_BEEPER")) { return source("electromechanical.buzzer", library); }
        if (starts(library, "SENSOR", "MICROPHONE")) { return source("sensor", library); }
        if (library.startsWith("DISPLAY")) { return source("display", library); }
        if (starts(library, "FUSE", "VARISTOR")) { return source("protection", library); }
        if (library.startsWith("RF")) { return source("rf", library); }
        if (starts(library, "MODULE", "AUDIO_MODULE", "CONVERTER")) { return source("module", library); }
        if (library.startsWith("TESTPOINT")) { return source("mechanical.test-point", library); }
        if (starts(library, "FIDUCIAL", "MOUNTING", "CALIBRATION", "HEATSINK",
                "PANELIZATION", "SYMBOL", "NETTIE")) { return source("mechanical", library); }
        return null;
    }

    private static Result source(String categoryId, String library) {
        return r(categoryId, .99, "KiCad 官方库名 " + library + " 明确指向该类别");
    }

    private static boolean starts(String text, String... values) {
        for (String value : values) { if (text.startsWith(value)) { return true; } }
        return false;
    }

    private static boolean isExact(String text, String... values) {
        String normalized = text.trim();
        for (String value : values) { if (normalized.equals(value)) { return true; } }
        return false;
    }

    private static boolean isGrid(Footprint fp) {
        if (fp == null || fp.getPads().size() < 4) { return false; }
        java.util.Set<Long> xs = new java.util.HashSet<>(), ys = new java.util.HashSet<>();
        fp.getPads().forEach(p -> {
            xs.add(Math.round(p.getX() * 1000));
            ys.add(Math.round(p.getY() * 1000));
        });
        return xs.size() > 1 && ys.size() > 1 && xs.size() * ys.size() >= fp.getPads().size();
    }
    private static boolean has(String text, String... values) {
        for (String value : values) { if (text.contains(value)) { return true; } }
        return false;
    }
    private static Result r(String id, double confidence, String reason) {
        return new Result(id, confidence, reason);
    }

    public static Result applySuggestion(Package pkg, boolean preserveVerified) {
        if (preserveVerified && (pkg.getReviewState() == Package.ReviewState.VERIFIED
                || "用户手工分类".equals(pkg.getClassificationReason()))) {
            return new Result(pkg.getCategoryId(), 1.0, "保留用户已确认分类");
        }
        Result result = classify(pkg);
        pkg.setCategoryId(result.categoryId);
        pkg.setClassificationConfidence(result.confidence);
        pkg.setClassificationReason(result.reason);
        if (pkg.getFootprint() == null || pkg.getFootprint().getPads().isEmpty())
            { pkg.setReviewState(Package.ReviewState.MISSING_FOOTPRINT); }
        else { if (result.categoryId == null || result.confidence < .8)
            { pkg.setReviewState(Package.ReviewState.NEEDS_REVIEW); }
        else { pkg.setReviewState(Package.ReviewState.AUTO_CLASSIFIED); } }
        return result;
    }
}
