package org.openpnp.stencil;

import java.util.*;
import com.google.gson.*;

/**
 * Geometry revision 17 datums, ported from the app center source, not its outputs.
 */
public final class StencilPlan {

    public final StencilParameters p;

    public final double jawWidth, jawLeft, jawRight, jawCenter, floor, pcbTop, pocketFloor, pocketDepth, jawFront, jawZ, hingeY, hingeZ, bearingOffset, baseLeft, baseRight, baseFront, baseRear, baseWidth, reliefDepth, sideDepth, fingerRadius, fingerX, fingerY;

    public final List<double[]> relief = new ArrayList<>(), sideRelief = new ArrayList<>(), fingers = new ArrayList<>();

    public final double[] screws, channel;

    public StencilPlan(StencilParameters p) {
        this.p = p;
        jawWidth = p.n("pcb_length") + 8 + p.n("quick_left_extension") + p.n("quick_right_extension");
        jawLeft = -(p.n("pcb_length") + 8) / 2 - p.n("quick_left_extension");
        jawRight = (p.n("pcb_length") + 8) / 2 + p.n("quick_right_extension");
        jawCenter = (jawLeft + jawRight) / 2;
        floor = Math.max(p.n("base_floor"), 2.8 - 1.4 + 2.5 + p.n("dock_clearance") + .7 - p.n("pcb_thickness") - p.n("stencil_gap"));
        pcbTop = floor + p.n("pcb_thickness");
        pocketFloor = floor - p.n("pcb_depth_compensation");
        pocketDepth = p.n("pcb_thickness") + p.n("pcb_depth_compensation");
        jawFront = p.n("pcb_width") / 2 + p.n("top_distance");
        jawZ = pcbTop + p.n("stencil_gap") - 2.8;
        hingeY = jawFront + 20;
        hingeZ = jawZ + 1.4;
        bearingOffset = 3.2 + p.n("pivot_axial_clearance") / 2 + (p.n("ear_length") - 2) / 2;
        double margin = 2.5 + p.n("ear_length") + 2.2;
        baseLeft = jawLeft - margin - p.n("left_extension");
        baseRight = jawRight + margin + p.n("right_extension");
        baseWidth = baseRight - baseLeft;
        baseFront = -p.n("pcb_width") / 2 - p.n("bottom_extension");
        baseRear = jawFront + 24.6;
        screws = new double[(int) p.n("screw_count")];
        for (int i = 0; i < screws.length; i++) {
            screws[i] = jawLeft + 5 + i * (jawWidth - 10) / (screws.length - 1);
        }
        reliefDepth = Math.max(0, Math.min(p.n("relief_depth"), Math.min(pocketFloor - 1.2, pcbTop - 1.6)));
        sideDepth = Math.max(0, Math.min(p.n("side_relief_depth"), pcbTop - 1.6));
        boolean bottom = p.s("finger_position").equals("bottom_center");
        fingerRadius = bottom ? Math.min(Math.min(6, p.n("pcb_width") / 4), p.n("pcb_length") / 4) : Math.min(6, p.n("pcb_width") / 4);
        double r = fingerRadius;
        // Public radius must follow both dimensions in bottom mode.
        actualFingerRadius = r;
        fingerX = p.n("pcb_length") / 2 + r - Math.min(2.5, r * .65);
        fingerY = -p.n("pcb_width") / 2 - r + Math.min(2.5, r * .65);
        if (bottom) {
            fingers.add(new double[] { 0, fingerY });
            channel = new double[] { 0, (baseFront - .2 + fingerY) / 2, 2 * r, fingerY - baseFront + .2 };
        } else {
            fingers.add(new double[] { -fingerX, 0 });
            fingers.add(new double[] { fingerX, 0 });
            channel = null;
        }
        double guard = fingerX + r + 3;
        List<double[]> rows = grid(baseFront + 3, jawFront - 5);
        if (reliefDepth > 1e-6) {
            cells(grid(Math.max(baseLeft + 3, -guard), Math.min(baseRight - 3, guard)), rows, relief);
        }
        List<double[]> cols = grid(baseLeft + 3, -guard - 3);
        cols.addAll(grid(guard + 3, baseRight - 3));
        if (sideDepth > 1e-6) {
            cells(cols, rows, sideRelief);
        }
    }

    public final double actualFingerRadius;

    private static List<double[]> grid(double a, double b) {
        List<double[]> r = new ArrayList<>();
        if (b - a < 3) {
            return r;
        }
        int n = Math.max(1, (int) Math.ceil((b - a + 3) / 18));
        double size = (b - a - (n - 1) * 3) / n;
        for (int i = 0; i < n; i++) {
            r.add(new double[] { a + size / 2 + i * (size + 3), size });
        }
        return r;
    }

    private void cells(List<double[]> cols, List<double[]> rows, List<double[]> out) {
        for (double[] x : cols) {
            for (double[] y : rows) {
                double[] c = { x[0], y[0], x[1], y[1] };
                boolean safe = true;
                for (double[] f : fingers) {
                    if (sq(Math.max(Math.abs(c[0] - f[0]) - c[2] / 2, 0)) + sq(Math.max(Math.abs(c[1] - f[1]) - c[3] / 2, 0)) <= sq(actualFingerRadius + 3) + 1e-8) {
                        safe = false;
                    }
                }
                if (channel != null && sq(Math.max(Math.abs(c[0] - channel[0]) - (c[2] + channel[2]) / 2, 0)) + sq(Math.max(Math.abs(c[1] - channel[1]) - (c[3] + channel[3]) / 2, 0)) <= 9 + 1e-8) {
                    safe = false;
                }
                if (safe) {
                    out.add(c);
                }
            }
        }
    }

    private static double sq(double x) {
        return x * x;
    }

    public double[] engravingZone() {
        if (p.s("emboss_part").equals("stencil_upper")) {
            return new double[] { jawLeft + 1, .5, jawRight - 1, 9.5 };
        }
        double[] z = { baseLeft + 1.5, baseFront + .5, baseRight - 1.5, -p.n("pcb_width") / 2 - p.n("pcb_clearance") - .5 };
        if (channel != null) {
            if (-actualFingerRadius - 3 - z[0] >= z[2] - actualFingerRadius - 3) {
                z[2] = -actualFingerRadius - 3;
            } else {
                z[0] = actualFingerRadius + 3;
            }
        }
        return z;
    }

    public String text() {
        String name = p.s("emboss_text").isEmpty() ? p.s("model") : p.s("emboss_text").replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            name = "PCB 夹具";
        }
        return name + (p.b("emboss_dimensions") ? "  |  PCB " + fmt(p.n("pcb_length")) + " x " + fmt(p.n("pcb_width")) + " x " + fmt(p.n("pcb_thickness")) : "");
    }

    public static String fmt(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    public JsonObject json() {
        JsonObject o = new Gson().toJsonTree(this).getAsJsonObject();
        o.remove("p");
        o.addProperty("geometry_revision", 17);
        return o;
    }

    public String instructions() {
        return "钢网夹具设计 · " + StencilParameters.PRODUCT_MODEL + "\n夹具型号：" + p.s("model") + "\nPCB：" + p.n("pcb_length") + " × " + p.n("pcb_width") + " × " + p.n("pcb_thickness") + " mm\n" + "1. 首次使用某种打印机或材料，先生成九组转轴测试件，选择顺畅且无明显晃动的一组，将 C 值填入转轴孔直径补偿。\n" + "2. 核对 PCB 尺寸、夹口和钢网参数，再生成三件打印模型。分别打印底板、下夹片、上夹片，保持打印姿态页及导出文件的朝向。下夹片螺母槽朝上，上夹片螺钉沉孔朝上。\n" + "3. 下夹片放入 M2.5 螺母；上夹片用 " + (int) p.n("screw_count") + " 颗 M2.5×" + (int) p.n("screw_length") + " 螺钉夹紧钢网（最长支持 6 mm）。\n" + "4. 放置 PCB。钢网空白边距为 " + p.n("stencil_margin") + " mm，夹口距离 " + p.n("top_distance") + " mm，夹入深度 " + p.n("grip_depth") + " mm。\n" + "5. 将夹片翻至接近 90°，扁轴向下放入开口轴座，翻下刷印。拆卸前先翻至 88～90°，再向上提起。\n" + "底板保留 PCB 下方至少 1.2 mm、侧边至少 1.6 mm，手指孔周围保留 3 mm 实心圈；刻字底厚至少 0.4 mm。\n" + "钢网应为无框、平直空白上边缘；钢网未张紧，刷印时须贴合 PCB。首次打印检查公差、夹紧力与释放手感。\n" + "参数可保存为启动设置、命名配置，或导入/导出 JSON；打印文件可选 STEP、STL、3MF 或完整 ZIP。\n" + "校验报告提供几何及干涉检查结果，实际打印后仍需试配。\n";
    }
}
