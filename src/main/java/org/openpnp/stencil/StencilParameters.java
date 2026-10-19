package org.openpnp.stencil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;

/**
 * Millimetre parameter contract compatible with Stencil Studio 1.14.0 schema 1.
 */
public final class StencilParameters {

    public static final String PRODUCT_MODEL = "YAA2000-GWJJ";

    public static final class Field {

        public final String key, label, group;

        public final Object value;

        public final double min, max, step;

        public final String[] choices;

        Field(String key, String label, String group, Object value, double min, double max, double step, String... choices) {
            this.key = key;
            this.label = label;
            this.group = group;
            this.value = value;
            this.min = min;
            this.max = max;
            this.step = step;
            this.choices = choices;
        }
    }

    public static final Map<String, Field> FIELDS = new LinkedHashMap<>();

    private static void n(String k, String l, String g, double v, double a, double b, double s) {
        FIELDS.put(k, new Field(k, l, g, v, a, b, s));
    }

    private static void s(String k, String l, String g, Object v, String... c) {
        FIELDS.put(k, new Field(k, l, g, v, 0, 0, 0, c));
    }

    static {
        s("model", "夹具型号", "基本尺寸", "");
        n("pcb_length", "PCB 长度", "基本尺寸", 33, 15, 180, .1);
        n("pcb_width", "PCB 宽度", "基本尺寸", 102.3, 10, 180, .1);
        n("pcb_thickness", "PCB 厚度", "基本尺寸", 1.6, .6, 3.2, .05);
        n("top_distance", "PCB 到夹口距离", "基本尺寸", 4, 3, 35, .1);
        n("grip_depth", "钢网夹入深度", "基本尺寸", 6, 3, 9, .1);
        n("stencil_margin", "钢网上边空白距离", "基本尺寸", 10, 6, 60, .1);
        n("stencil_thickness", "钢网厚度", "基本尺寸", .12, .05, .3, .01);
        s("screw_count", "M2.5 螺钉数量", "基本尺寸", 2, "2", "3", "4");
        s("screw_length", "M2.5 螺钉长度", "基本尺寸", 4, "4", "5", "6");
        n("pcb_clearance", "PCB 侧向间隙", "打印公差", .15, .05, .4, .05);
        n("pcb_depth_compensation", "PCB 槽深补偿", "打印公差", 0, -.4, .4, .05);
        n("pivot_compensation", "转轴孔直径补偿", "打印公差", .05, 0, .4, .05);
        n("pivot_axial_clearance", "转轴总轴向间隙", "打印公差", .05, 0, .4, .05);
        n("dock_clearance", "夹片座间隙", "打印公差", .2, .1, .4, .05);
        n("stencil_gap", "钢网附加间隙", "打印公差", 0, 0, .3, .05);
        n("base_floor", "底板最小底厚", "结构与减重", 2, 1.6, 4, .1);
        n("left_extension", "底板左扩展", "结构与减重", 0, 0, 80, 1);
        n("right_extension", "底板右扩展", "结构与减重", 0, 0, 80, 1);
        n("bottom_extension", "底板下扩展", "结构与减重", 10, 3, 80, 1);
        n("quick_left_extension", "快拆夹片左扩展", "结构与减重", 0, 0, 80, 1);
        n("quick_right_extension", "快拆夹片右扩展", "结构与减重", 0, 0, 80, 1);
        n("relief_depth", "底部网格减重深度", "结构与减重", .8, 0, 2, .1);
        n("side_relief_depth", "侧边减重深度", "结构与减重", 2.4, 0, 4, .1);
        n("ear_length", "轴耳长度", "结构与减重", 6.5, 5.5, 12, .5);
        s("finger_position", "取板手指孔位置", "结构与减重", "sides", "sides", "bottom_center");
        s("engrave", "启用凹刻文字", "刻字", false);
        n("engrave_depth", "旧版刻字深度（兼容参数）", "刻字", .4, .2, .6, .05);
        s("emboss_text", "凹刻文字（留空使用型号）", "刻字", "");
        s("emboss_dimensions", "同排加入 PCB 尺寸", "刻字", true);
        n("emboss_size", "实际字高", "刻字", 4, 1, 20, .5);
        n("emboss_height", "凹刻深度", "刻字", 2, .2, 4, .1);
        s("emboss_font", "字体", "刻字", "default", "default", "yahei", "simhei", "simsun", "arial");
        s("emboss_part", "凹刻部件", "刻字", "pcb_base", "pcb_base", "stencil_upper");
        s("emboss_position", "文字定位", "刻字", "auto", "auto", "manual");
        n("emboss_x", "文字中心 X", "刻字", 0, -300, 300, .5);
        n("emboss_y", "文字中心 Y", "刻字", 0, -300, 300, .5);
        n("emboss_angle", "文字旋转角度", "刻字", 0, -180, 180, 1);
    }

    private final JsonObject data;

    public StencilParameters(JsonObject input) {
        data = new JsonObject();
        data.addProperty("schema_version", 1);
        for (Field f : FIELDS.values()) {
            data.add(f.key, new Gson().toJsonTree(f.value));
        }
        for (Map.Entry<String, JsonElement> e : input.entrySet()) {
            String k = e.getKey();
            JsonElement v = e.getValue();
            if (!k.equals("schema_version") && !FIELDS.containsKey(k)) {
                fail("未知参数：" + k);
            }
            if (v == null || !v.isJsonPrimitive()) {
                fail("参数必须为单个值：" + k);
            }
            if (k.equals("schema_version")) {
                if (!v.getAsJsonPrimitive().isNumber() || v.getAsDouble() != 1) {
                    fail("不支持的参数版本");
                }
            } else {
                Field f = FIELDS.get(k);
                if (f.value instanceof Number) {
                    if (!v.getAsJsonPrimitive().isNumber()) {
                        fail(f.label + " 必须为数字");
                    }
                    double x = v.getAsDouble();
                    if (!Double.isFinite(x)) {
                        fail(f.label + " 必须为有限数字");
                    }
                    if (f.choices.length == 0 && (x < f.min - 1e-8 || x > f.max + 1e-8)) {
                        fail(f.label + " 范围为 " + f.min + "～" + f.max);
                    }
                    if (f.choices.length > 0 && !Arrays.asList(f.choices).contains(Integer.toString((int) x))) {
                        fail(f.label + " 选项不正确");
                    }
                    if (f.choices.length > 0 && x != (int) x) {
                        fail(f.label + " 必须为整数");
                    }
                    if (Arrays.asList("pcb_depth_compensation", "pivot_compensation", "pivot_axial_clearance").contains(k) && Math.abs(x / .05 - Math.rint(x / .05)) > 1e-7) {
                        fail(f.label + " 步长必须为 0.05 mm");
                    }
                } else {
                    if (f.value instanceof Boolean) {
                        if (!v.getAsJsonPrimitive().isBoolean()) {
                            fail(f.label + " 必须为开关值");
                        }
                    } else {
                        if (!v.getAsJsonPrimitive().isString()) {
                            fail(f.label + " 必须为文字");
                        }
                        String raw = v.getAsString();
                        String x = raw.trim();
                        if (f.choices.length > 0 && !Arrays.asList(f.choices).contains(x)) {
                            fail(f.label + " 选项不正确");
                        }
                        if (k.equals("model") || k.equals("emboss_text")) {
                            if (x.length() > (k.equals("model") ? 60 : 160)) {
                                fail(f.label + " 过长");
                            }
                            for (char c : raw.toCharArray()) {
                                if (c < 32 && !(k.equals("emboss_text") && c == '\n')) {
                                    fail(f.label + " 包含控制字符");
                                }
                            }
                            if (k.equals("emboss_text") && x.split("\n", -1).length > 3) {
                                fail("凹刻文字最多三行");
                            }
                        }
                        v = new JsonPrimitive(x);
                    }
                }
            }
            data.add(k, new JsonParser().parse(v.toString()));
        }
        if (Math.abs(n("stencil_margin") - n("top_distance") - n("grip_depth")) > .02 + 1e-8) {
            fail("钢网上边空白距离必须等于 PCB 到夹口距离 + 夹入深度");
        }
        if (n("screw_count") == 4 && n("pcb_length") + n("quick_left_extension") + n("quick_right_extension") < 22) {
            fail("4 颗螺钉要求 PCB 长度加快拆左右扩展至少 22 mm");
        }
    }

    public static StencilParameters factory() {
        try (InputStream in = StencilParameters.class.getResourceAsStream("factory-defaults.json")) {
            return new StencilParameters(new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static StencilParameters parse(String text) {
        JsonObject root = new Gson().fromJson(text, JsonObject.class);
        if (root == null) {
            throw new IllegalArgumentException("参数文件为空");
        }
        if (root.has("app_id")) {
            Set<String> allowed = new HashSet<>(Arrays.asList("app_id", "app_version", "schema_version", "config"));
            if (!root.get("app_id").getAsString().equals("stencil-studio") || !root.has("schema_version") || !root.get("schema_version").getAsJsonPrimitive().isNumber() || root.get("schema_version").getAsDouble() != 1 || !root.has("config")) {
                fail("不是支持的钢网夹具参数文件");
            }
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!allowed.contains(entry.getKey())) {
                    fail("未知文件字段：" + entry.getKey());
                }
            }
            root = root.getAsJsonObject("config");
        }
        return new StencilParameters(root);
    }

    public double n(String k) {
        return data.get(k).getAsDouble();
    }

    public String s(String k) {
        return data.get(k).getAsString();
    }

    public boolean b(String k) {
        return data.get(k).getAsBoolean();
    }

    public JsonObject json() {
        return new JsonParser().parse(data.toString()).getAsJsonObject();
    }

    public StencilParameters with(String k, Object value) {
        JsonObject o = json();
        o.add(k, new Gson().toJsonTree(value));
        return new StencilParameters(o);
    }

    public JsonObject envelope() {
        JsonObject o = new JsonObject();
        o.addProperty("app_id", "stencil-studio");
        o.addProperty("app_version", "1.14.0-pro-java1");
        o.addProperty("schema_version", 1);
        o.add("config", json());
        return o;
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }

    public static String displayChoice(String s) {
        switch(s) {
            case "sides":
                return "左右两侧";
            case "bottom_center":
                return "底部中间";
            case "default":
                return "默认中文字体";
            case "yahei":
                return "微软雅黑";
            case "simhei":
                return "黑体";
            case "simsun":
                return "宋体";
            case "arial":
                return "Arial";
            case "pcb_base":
                return "底板顶面";
            case "stencil_upper":
                return "上夹片顶面";
            case "auto":
                return "自动放在空白区";
            case "manual":
                return "手动定位（可点击示意图）";
            default:
                return s;
        }
    }
}
