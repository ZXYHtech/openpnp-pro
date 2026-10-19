package org.openpnp.stencil;

import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.*;
import java.util.*;
import com.google.gson.*;

/**
 * Java builds the CAD program; the packaged native worker only evaluates BRep operations.
 */
public final class StencilCadTask {

    private final JsonArray ops = new JsonArray(), exports = new JsonArray(), checks = new JsonArray(), measurements = new JsonArray();

    private final Gson gson = new Gson();

    private int op(String type, Object... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        for (int i = 0; i < kv.length; i += 2) {
            o.add(kv[i].toString(), gson.toJsonTree(kv[i + 1]));
        }
        ops.add(o);
        return ops.size() - 1;
    }

    private int box(double x, double y, double z, double w, double h, double d) {
        return op("box", "x", x, "y", y, "z", z, "w", w, "h", h, "d", d);
    }

    private int rounded(double x, double y, double z, double w, double h, double d, double r) {
        return op("rounded", "x", x, "y", y, "z", z, "w", w, "h", h, "d", d, "r", Math.min(r, Math.min(w / 3, h / 3)));
    }

    private int cylinder(double x, double y, double z, double r, double d, int axis) {
        return op("cylinder", "origin", new double[] { x, y, z }, "r", r, "d", d, "axis", new int[] { axis == 0 ? 1 : 0, 0, axis == 0 ? 0 : 1 });
    }

    private int bool(String type, int... refs) {
        return op(type, "refs", refs);
    }

    private int compound(List<Integer> refs) {
        return op("compound", "refs", refs);
    }

    private int move(int ref, double x, double y, double z) {
        return transform(ref, 0, new double[] { 0, 0, 0 }, new double[] { x, y, z });
    }

    private int transform(int ref, double angle, double[] origin, double[] move) {
        return op("transform", "ref", ref, "angle", angle, "origin", origin, "axis", new int[] { 1, 0, 0 }, "move", move);
    }

    private int profile(List<double[][]> contours, double z, double depth) {
        return op("profile", "contours", contours, "z", z, "d", depth);
    }

    private void check(int a, int b, String label, boolean full, double tolerance) {
        JsonObject o = new JsonObject();
        o.addProperty("a", a);
        o.addProperty("b", b);
        o.addProperty("label", label);
        o.addProperty("full", full);
        o.addProperty("tolerance", tolerance);
        checks.add(o);
    }

    private void export(int ref, String name, int solids, boolean print, boolean mesh) {
        JsonObject o = new JsonObject();
        o.addProperty("ref", ref);
        o.addProperty("name", name);
        o.addProperty("solids", solids);
        o.addProperty("print", print);
        o.addProperty("mesh", mesh);
        exports.add(o);
    }

    private int base(StencilParameters p, StencilPlan d) {
        double h = d.pcbTop, jc = p.n("dock_clearance"), r = d.actualFingerRadius;
        int body = rounded((d.baseLeft + d.baseRight) / 2, (d.baseFront + d.baseRear) / 2, 0, d.baseWidth, d.baseRear - d.baseFront, h, 1.5);
        body = bool("cut", body, rounded(0, 0, d.pocketFloor, p.n("pcb_length") + 2 * p.n("pcb_clearance"), p.n("pcb_width") + 2 * p.n("pcb_clearance"), d.pocketDepth + .2, .25));
        for (double[] f : d.fingers) {
            body = bool("cut", body, cylinder(f[0], f[1], -.1, r, h + .2, 2));
        }
        if (d.channel != null) {
            body = bool("cut", body, box(d.channel[0], d.channel[1], -.1, d.channel[2], d.channel[3], h + .2));
        }
        double y = d.jawFront - .2 - jc, depth = d.baseRear - y + .2;
        body = bool("cut", body, box(d.jawCenter, y + depth / 2, d.jawZ, d.jawWidth + 2 * (3.2 + jc), depth, h - d.jawZ + .3));
        for (int side : new int[] { -1, 1 }) {
            double edge = side < 0 ? d.jawLeft : d.jawRight, x = edge + side * d.bearingOffset;
            body = bool("union", body, box(x, d.hingeY, 0, p.n("ear_length") - 2, 8.4, d.hingeZ + 4.2));
            double cx = edge + side * (2.5 + p.n("ear_length") / 2), length = p.n("ear_length") + .4;
            body = bool("cut", body, cylinder(cx - length / 2, d.hingeY, d.hingeZ, 2.5 + p.n("pivot_compensation") / 2, length, 0), box(cx, d.hingeY, d.hingeZ, length, 2.8 + 2 * jc, 4.4));
        }
        for (double x : d.screws) {
            body = bool("cut", body, rounded(x, d.jawFront + 12.5, -.1, 4.4, 6.4, h + .2, 2.1));
        }
        List<Integer> pockets = new ArrayList<>();
        for (double[] c : d.relief) {
            pockets.add(rounded(c[0], c[1], -.1, c[2], c[3], d.reliefDepth + .1, 1));
        }
        for (double[] c : d.sideRelief) {
            pockets.add(rounded(c[0], c[1], -.1, c[2], c[3], d.sideDepth + .1, 1));
        }
        if (!pockets.isEmpty()) {
            body = bool("cut", body, compound(pockets));
        }
        return engrave(body, p, d, "pcb_base");
    }

    private int lower(StencilParameters p, StencilPlan d) {
        int body = rounded(d.jawCenter, 10, 0, d.jawWidth, 20, 2.8, .6);
        for (int side : new int[] { -1, 1 }) {
            double edge = side < 0 ? d.jawLeft : d.jawRight, len = p.n("ear_length");
            int wing = box(edge + side * 1.1, 18.5, 0, 4.2, 3, 2.8);
            // Cylinder axis direction is immaterial once its starting X is normalized.
            int pin = cylinder(side < 0 ? edge - 2.5 - len : edge + 2.5, 20, 1.4, 2.5, len, 0);
            pin = bool("common", pin, box(edge + side * (2.5 + len / 2), 20, 0, len + .2, 5.2, 2.8));
            body = bool("union", body, wing, pin);
        }
        for (double x : d.screws) {
            List<double[][]> hex = new ArrayList<>();
            double[][] pts = new double[6][2];
            for (int i = 0; i < 6; i++) {
                double a = i * Math.PI / 3;
                pts[i] = new double[] { x + 5.3 / 2 / Math.cos(Math.PI / 6) * Math.cos(a), 13 + 5.3 / 2 / Math.cos(Math.PI / 6) * Math.sin(a) };
            }
            hex.add(pts);
            body = bool("cut", body, cylinder(x, 13, -.1, 1.4, 3, 2), profile(hex, -.1, 2.2));
        }
        return transform(body, 180, new double[] { 0, 0, 0 }, new double[] { 0, 20, 2.8 });
    }

    private int upper(StencilParameters p, StencilPlan d) {
        int body = rounded(d.jawCenter, 10, 0, d.jawWidth, 20, 2.8, .6);
        for (double x : d.screws) {
            body = bool("cut", body, cylinder(x, 13, -.1, 1.4, 3, 2), cylinder(x, 13, 1, 1.8, .5, 2), cylinder(x, 13, 1.4, 2.6, 1.6, 2));
        }
        return engrave(body, p, d, "stencil_upper");
    }

    public static Font font(String key) {
        String family;
        switch(key) {
            case "simhei":
                family = "SimHei";
                break;
            case "simsun":
                family = "SimSun";
                break;
            case "arial":
                family = "Arial";
                break;
            default:
                family = "Microsoft YaHei";
        }
        Font f = new Font(family, key.equals("default") || key.equals("arial") ? Font.BOLD : Font.PLAIN, 1000);
        if (f.getFamily().equals("Dialog") && !family.equals("Dialog")) {
            f = new Font("Dialog", Font.PLAIN, 1000);
        }
        return f;
    }

    private static List<double[][]> outlines(String text, Font font, double height, double x, double y, double angle) {
        if (font.canDisplayUpTo(text) != -1) {
            throw new IllegalArgumentException("所选字体无法显示全部刻字，请选择中文字体");
        }
        Shape shape = font.createGlyphVector(new FontRenderContext(null, true, true), text).getOutline();
        Rectangle2D b = shape.getBounds2D();
        if (b.getHeight() <= 0) {
            throw new IllegalArgumentException("刻字没有可用轮廓");
        }
        double scale = height / b.getHeight();
        AffineTransform tx = new AffineTransform();
        tx.translate(x, y);
        tx.rotate(Math.toRadians(angle));
        tx.scale(scale, -scale);
        tx.translate(-b.getCenterX(), -b.getCenterY());
        PathIterator it = tx.createTransformedShape(shape).getPathIterator(null, .008);
        List<double[][]> result = new ArrayList<>();
        List<double[]> c = new ArrayList<>();
        double[] pt = new double[6];
        while (!it.isDone()) {
            int t = it.currentSegment(pt);
            if (t == PathIterator.SEG_MOVETO) {
                c = new ArrayList<>();
                c.add(new double[] { pt[0], pt[1] });
            } else {
                if (t == PathIterator.SEG_LINETO) {
                    double[] last = c.get(c.size() - 1);
                    if (Math.hypot(pt[0] - last[0], pt[1] - last[1]) > 1e-7) {
                        c.add(new double[] { pt[0], pt[1] });
                    }
                } else {
                    if (t == PathIterator.SEG_CLOSE) {
                        if (c.size() > 1 && Math.hypot(c.get(0)[0] - c.get(c.size() - 1)[0], c.get(0)[1] - c.get(c.size() - 1)[1]) < 1e-7) {
                            c.remove(c.size() - 1);
                        }
                        if (c.size() > 2) {
                            result.add(c.toArray(new double[0][]));
                        }
                    }
                }
            }
            it.next();
        }
        return result;
    }

    private int engrave(int body, StencilParameters p, StencilPlan d, String part) {
        if (!p.b("engrave") || !p.s("emboss_part").equals(part)) {
            return body;
        }
        double[] zone = d.engravingZone();
        boolean auto = p.s("emboss_position").equals("auto");
        double x = auto ? (zone[0] + zone[2]) / 2 : p.n("emboss_x"), y = auto ? (zone[1] + zone[3]) / 2 : p.n("emboss_y");
        List<double[][]> contours = outlines(d.text(), font(p.s("emboss_font")), p.n("emboss_size"), x, y, p.n("emboss_angle"));
        if (auto) {
            for (double[][] c : contours) {
                for (double[] v : c) {
                    if (v[0] < zone[0] - 1e-5 || v[0] > zone[2] + 1e-5 || v[1] < zone[1] - 1e-5 || v[1] > zone[3] + 1e-5) {
                        throw new IllegalArgumentException("同排凹刻文字放不下：请缩短文字、减小字高，或点击选择空白位置");
                    }
                }
            }
        }
        double z = part.equals("pcb_base") ? d.pcbTop : 2.8, depth = p.n("emboss_height");
        int backing = profile(contours, z, -depth - .4);
        check(body, backing, "凹刻必须完整落在实体内并保留 0.4 mm 底厚", true, 1e-4);
        int tool = profile(contours, z + .02, -depth - .02);
        if (part.equals("pcb_base")) {
            check(box(d.jawCenter, (-p.n("pcb_width") / 2 + d.jawFront + p.n("grip_depth")) / 2, z - depth - .05, d.jawWidth, d.jawFront + p.n("grip_depth") + p.n("pcb_width") / 2, depth + .1), tool, "凹刻不能进入 PCB 或钢网工作区域", false, 1e-5);
        }
        return bool("cut", body, tool);
    }

    private int[] assembly(StencilParameters p, StencilPlan d, int b, int l, int u, double angle, double lift) {
        int low = transform(l, 180, new double[] { 0, 0, 0 }, new double[] { 0, d.jawFront + 20, d.jawZ + 2.8 });
        int up = move(u, 0, d.jawFront, d.pcbTop + p.n("stencil_gap") + p.n("stencil_thickness"));
        if (angle != 0 || lift != 0) {
            double[] origin = { 0, d.hingeY, d.hingeZ }, shift = { 0, 0, lift };
            low = transform(low, -angle, origin, shift);
            up = transform(up, -angle, origin, shift);
        }
        return new int[] { b, low, up };
    }

    private void interference(int[] ids, String label) {
        check(ids[0], ids[1], label + " 底板/下夹片", false, 1e-5);
        check(ids[0], ids[2], label + " 底板/上夹片", false, 1e-5);
        check(ids[1], ids[2], label + " 两夹片", false, 1e-5);
    }

    public JsonObject fixture(StencilParameters p) {
        StencilPlan d = new StencilPlan(p);
        int b = base(p, d), l = lower(p, d), u = upper(p, d);
        int[] rest = assembly(p, d, b, l, u, 0, 0);
        interference(rest, "闭合");
        for (int angle = 5; angle <= 90; angle += 5) {
            interference(assembly(p, d, b, l, u, angle, 0), "翻转 " + angle + "°");
        }
        interference(assembly(p, d, b, l, u, 88, 1), "88° 抬升 1 mm");
        interference(assembly(p, d, b, l, u, 90, 8), "90° 抬升 8 mm");
        double seat = d.pcbTop + p.n("stencil_gap") + p.n("stencil_thickness") + 2.8 - 1.4;
        List<Integer> shafts = new ArrayList<>();
        for (double x : d.screws) {
            shafts.add(cylinder(x, d.jawFront + 13, seat - 6, 1.25, 6, 2));
        }
        int shaft = compound(shafts);
        for (int angle = 0; angle <= 90; angle += 5) {
            check(b, transform(shaft, -angle, new double[] { 0, d.hingeY, d.hingeZ }, new double[] { 0, 0, 0 }), "6 mm 螺钉扫掠 " + angle + "°", false, 1e-6);
        }
        check(b, transform(shaft, -88, new double[] { 0, d.hingeY, d.hingeZ }, new double[] { 0, 0, 1 }), "6 mm 螺钉抬升", false, 1e-6);
        int board = box(0, 0, d.pocketFloor, p.n("pcb_length"), p.n("pcb_width"), p.n("pcb_thickness"));
        check(b, board, "PCB 槽位无干涉", false, 1e-5);
        export(b, "pcb_base", 1, true, true);
        export(l, "stencil_lower", 1, true, true);
        export(u, "stencil_upper", 1, true, true);
        export(compound(Arrays.asList(rest[0], rest[1], rest[2])), "assembly", 3, false, false);
        int solid = base(p.with("relief_depth", 0).with("side_relief_depth", 0), new StencilPlan(p.with("relief_depth", 0).with("side_relief_depth", 0)));
        JsonObject measurement = new JsonObject();
        measurement.addProperty("name", "solid_base");
        measurement.addProperty("ref", solid);
        measurements.add(measurement);
        return json(p, "fixture");
    }

    private int label(int body, String text, double height, double x, double y, double z, double maxWidth) {
        List<double[][]> c = outlines(text, new Font("Arial", Font.BOLD, 1000), height, x, y, 0);
        for (double[][] co : c) {
            for (double[] v : co) {
                if (Math.abs(v[0] - x) > maxWidth / 2 + 1e-5) {
                    throw new IllegalArgumentException("转轴测试标签放不下");
                }
            }
        }
        return bool("cut", body, profile(c, z, -.5));
    }

    public JsonObject coupons(StencilParameters original) {
        List<Integer> plate = new ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            JsonObject v = original.json();
            v.addProperty("engrave", false);
            v.addProperty("pcb_width", 10);
            v.addProperty("model", "");
            v.addProperty("relief_depth", 0);
            v.addProperty("side_relief_depth", 0);
            v.addProperty("left_extension", 0);
            v.addProperty("right_extension", 0);
            v.addProperty("screw_count", 2);
            v.addProperty("pivot_compensation", i * .05);
            StencilParameters p = new StencilParameters(v);
            StencilPlan d = new StencilPlan(p);
            double r = 4.4;
            int b = base(p, d), l = lower(p, d), u = upper(p, d);
            int bc = bool("common", b, box((d.baseLeft + d.baseRight) / 2, d.hingeY, -.1, d.baseWidth + .2, 2 * r, 20));
            bc = bool("union", bc, box(0, d.hingeY - r - 3.5, 0, d.baseWidth, 7.2, d.jawZ));
            String text = String.format(Locale.ROOT, "C%.2f", i * .05);
            bc = label(bc, text, 2.5, 0, d.hingeY - r - 3.4, d.jawZ - .35, d.baseWidth - 4);
            int lc = bool("common", l, box(0, 0, -.1, d.baseWidth + .2, 2 * r, 10));
            lc = label(lc, text, 2.1, 0, 2.3, 2.8 - .35, 24);
            for (double a : new double[] { 0, 30, 60, 90 }) {
                int[] ids = assembly(p, d, bc, lc, u, a, 0);
                check(ids[0], ids[1], text + " 翻转 " + a, false, 1e-5);
            }
            int[] lift = assembly(p, d, bc, lc, u, 90, 8);
            check(lift[0], lift[1], text + " 向上取出", false, 1e-5);
            double x = (i % 3) * (d.baseWidth + 6), y = (i / 3) * 38;
            plate.add(move(bc, x - d.baseLeft, y - (d.hingeY - r - 7.1), 0));
            double lowerMinX = d.jawLeft - 2.5 - p.n("ear_length");
            plate.add(move(lc, x - lowerMinX, y + 2 * r + 7.1 + 5, 0));
        }
        export(compound(plate), "hinge_test", 18, true, true);
        return json(original, "hinge_calibration");
    }

    private JsonObject json(StencilParameters p, String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("protocol", 1);
        o.addProperty("kind", kind);
        o.addProperty("geometry_revision", 17);
        o.add("parameters", p.json());
        o.add("ops", ops);
        o.add("checks", checks);
        o.add("exports", exports);
        o.add("measurements", measurements);
        return o;
    }
}
