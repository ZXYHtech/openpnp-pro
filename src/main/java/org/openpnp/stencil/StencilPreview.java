package org.openpnp.stencil;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.*;

/**
 * Local Java2D mesh renderer; no browser, WebGL, JavaFX or Python runtime.
 */
@SuppressWarnings("serial")
public final class StencilPreview extends JPanel {

    private StencilParameters parameters = StencilParameters.factory(), modelParameters;

    private List<StencilMesh> meshes = Collections.emptyList();

    private boolean threeD, printPose, coupons, place;

    private boolean references = true;

    private double yaw = -25, pitch = 55, zoom = 1, panX, panY, angle, lift, explode;

    private double scale = 1, cx, cy, centerX, centerY;

    private Point pressed;

    private boolean dragged;

    private BiConsumer<Double, Double> placement = (x, y) -> {
    };

    private javax.swing.Timer animation;

    private static final Color[] COLORS = { new Color(82, 120, 110), new Color(143, 174, 181), new Color(214, 172, 114) };

    public StencilPreview() {
        setPreferredSize(new Dimension(660, 520));
        setBackground(new Color(236, 241, 239));
        MouseAdapter mouse = new MouseAdapter() {

            @Override
            public void mousePressed(MouseEvent e) {
                pressed = e.getPoint();
                dragged = false;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressed == null) {
                    return;
                }
                double dx = e.getX() - pressed.x, dy = e.getY() - pressed.y;
                if (Math.abs(dx) + Math.abs(dy) > 2) {
                    dragged = true;
                }
                if (threeD && SwingUtilities.isLeftMouseButton(e) && !place) {
                    yaw += dx * .5;
                    pitch = Math.max(5, Math.min(175, pitch + dy * .5));
                } else {
                    panX += dx;
                    panY += dy;
                }
                pressed = e.getPoint();
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!place || dragged) {
                    return;
                }
                pick(e);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                zoom = Math.max(.15, Math.min(8, zoom * Math.pow(1.12, -e.getPreciseWheelRotation())));
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        setToolTipText("左键拖动旋转三维，右键拖动平移，滚轮缩放；点击定位模式下，点击顶面放置刻字");
    }

    public void parameters(StencilParameters p) {
        if (parameters.json().toString().equals(p.json().toString())) {
            return;
        }
        parameters = p;
        repaint();
    }

    public void model(StencilParameters p, List<StencilMesh> values, boolean coupons) {
        stop();
        modelParameters = p;
        meshes = values;
        this.coupons = coupons;
        angle = lift = explode = 0;
        fit();
    }

    public void threeD(boolean value) {
        threeD = value;
        repaint();
    }

    public void printPose(boolean value) {
        printPose = value;
        repaint();
    }

    public void placement(boolean value, BiConsumer<Double, Double> callback) {
        place = value;
        placement = callback;
        setCursor(Cursor.getPredefinedCursor(value ? Cursor.CROSSHAIR_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
    }

    public void top() {
        yaw = 0;
        pitch = 90;
        repaint();
    }

    public void references(boolean show) {
        references = show;
        repaint();
    }

    public void fit() {
        zoom = 1;
        panX = panY = 0;
        repaint();
    }

    public void release(double value, double lift) {
        stop();
        angle = value;
        this.lift = value >= 88 ? lift : 0;
        explode = 0;
        repaint();
    }

    public void explode() {
        stop();
        double from = explode, target = from > .5 ? 0 : 1;
        long start = System.nanoTime();
        animation = new javax.swing.Timer(25, e -> {
            double t = Math.min(1, (System.nanoTime() - start) / 850000000.0);
            explode = from + (target - from) * (t * t * (3 - 2 * t));
            repaint();
            if (t >= 1) {
                stop();
            }
        });
        animation.start();
    }

    public void animateRelease() {
        stop();
        explode = 0;
        long start = System.nanoTime();
        animation = new javax.swing.Timer(30, e -> {
            double t = (System.nanoTime() - start) / 1e9;
            angle = Math.min(90, t * 45);
            lift = t <= 2 ? 0 : Math.min(8, (t - 2) * 8);
            repaint();
            if (t >= 3) {
                stop();
            }
        });
        animation.start();
    }

    public void stop() {
        if (animation != null) {
            animation.stop();
            animation = null;
        }
    }

    private void pick(MouseEvent e) {
        if (threeD && (meshes.isEmpty() || coupons || angle != 0 || lift != 0 || explode != 0 || printPose)) {
            return;
        }
        StencilParameters p = threeD ? modelParameters : parameters;
        StencilPlan d = new StencilPlan(p);
        double x, y;
        if (!threeD) {
            x = (e.getX() - cx) / scale;
            y = (cy - e.getY()) / scale;
        } else {
            double a = Math.toRadians(yaw), b = Math.toRadians(pitch), z = parameters.s("emboss_part").equals("pcb_base") ? d.pcbTop : d.pcbTop + p.n("stencil_gap") + p.n("stencil_thickness") + 2.8;
            double xp = (e.getX() - cx) / scale, yp = (cy - e.getY()) / scale;
            double q = (yp + Math.cos(b) * z) / Math.sin(b);
            x = Math.cos(a) * xp + Math.sin(a) * q + centerX;
            y = -Math.sin(a) * xp + Math.cos(a) * q + centerY;
            if (parameters.s("emboss_part").equals("stencil_upper")) {
                y -= d.jawFront;
            }
        }
        placement.accept(Math.rint(x * 100) / 100, Math.rint(y * 100) / 100);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (threeD) {
                if (meshes.isEmpty()) {
                    g.setColor(new Color(50, 65, 60));
                    g.drawString("生成模型后，这里显示实际打印实体。", 24, 45);
                    return;
                }
                render3D(g);
            } else {
                render2D(g);
            }
            g.setColor(new Color(45, 65, 57));
            g.drawString(place ? "点击空白顶面放置凹刻文字；实体生成时会检查剩余底厚。" : threeD ? "实际网格 · 左键旋转 / 右键平移 / 滚轮缩放" : "结构示意 · 所有尺寸单位 mm", 16, getHeight() - 14);
        } catch (RuntimeException e) {
            g.setColor(Color.RED);
            g.drawString("预览不可用：" + e.getMessage(), 20, 40);
        } finally {
            g.dispose();
        }
    }

    private void render2D(Graphics2D g) {
        StencilPlan d = new StencilPlan(parameters);
        boolean upper = parameters.s("emboss_part").equals("stencil_upper") && place;
        double left = upper ? d.jawLeft : d.baseLeft, right = upper ? d.jawRight : d.baseRight, front = upper ? 0 : d.baseFront, rear = upper ? 20 : d.baseRear;
        scale = Math.max(.1, Math.min((getWidth() - 70) / (right - left), (getHeight() - 90) / (rear - front))) * zoom;
        cx = getWidth() / 2.0 - (left + right) / 2 * scale + panX;
        cy = getHeight() / 2.0 + (front + rear) / 2 * scale + panY;
        AffineTransform saved = g.getTransform();
        g.translate(cx, cy);
        g.scale(scale, -scale);
        g.setStroke(new BasicStroke((float) (1 / scale)));
        g.setColor(COLORS[upper ? 2 : 0]);
        g.fill(new RoundRectangle2D.Double(left, front, right - left, rear - front, 3, 3));
        if (!upper) {
            g.setColor(new Color(181, 202, 190));
            g.fill(new Rectangle2D.Double(-parameters.n("pcb_length") / 2, -parameters.n("pcb_width") / 2, parameters.n("pcb_length"), parameters.n("pcb_width")));
            g.setColor(getBackground());
            for (double[] f : d.fingers) {
                g.fill(new Ellipse2D.Double(f[0] - d.actualFingerRadius, f[1] - d.actualFingerRadius, 2 * d.actualFingerRadius, 2 * d.actualFingerRadius));
            }
            if (d.channel != null) {
                g.fill(new Rectangle2D.Double(d.channel[0] - d.channel[2] / 2, d.channel[1] - d.channel[3] / 2, d.channel[2], d.channel[3]));
            }
            g.setColor(COLORS[1]);
            g.fill(new RoundRectangle2D.Double(d.jawLeft, d.jawFront, d.jawWidth, 20, 1.2, 1.2));
            g.setColor(new Color(50, 70, 62));
            g.draw(new Line2D.Double(d.baseLeft, d.hingeY, d.baseRight, d.hingeY));
        }
        g.setColor(new Color(45, 60, 55));
        for (double x : d.screws) {
            double y = upper ? 13 : d.jawFront + 13;
            g.fill(new Ellipse2D.Double(x - 2.6, y - 2.6, 5.2, 5.2));
        }
        if (parameters.b("engrave")) {
            double[] z = d.engravingZone();
            double x = parameters.s("emboss_position").equals("auto") ? (z[0] + z[2]) / 2 : parameters.n("emboss_x"), y = parameters.s("emboss_position").equals("auto") ? (z[1] + z[3]) / 2 : parameters.n("emboss_y");
            // Upper clamp coordinates remain local when the full assembly schematic is displayed.
            if (!upper && parameters.s("emboss_part").equals("stencil_upper")) {
                y += d.jawFront;
            }
            Font f = StencilCadTask.font(parameters.s("emboss_font"));
            Shape s = f.createGlyphVector(g.getFontRenderContext(), d.text()).getOutline();
            Rectangle2D b = s.getBounds2D();
            double k = parameters.n("emboss_size") / Math.max(1, b.getHeight());
            AffineTransform tx = new AffineTransform();
            tx.translate(x, y);
            tx.rotate(Math.toRadians(parameters.n("emboss_angle")));
            tx.scale(k, -k);
            tx.translate(-b.getCenterX(), -b.getCenterY());
            g.setColor(new Color(25, 38, 33));
            g.fill(tx.createTransformedShape(s));
        }
        g.setTransform(saved);
        g.setColor(new Color(40, 60, 50));
        g.drawString("PCB " + parameters.n("pcb_length") + " × " + parameters.n("pcb_width") + " × " + parameters.n("pcb_thickness") + "  |  底板 " + StencilPlan.fmt(d.baseWidth) + " × " + StencilPlan.fmt(d.baseRear - d.baseFront), 16, 23);
    }

    private double[] world(double x, double y, double z, int part, StencilPlan d) {
        if (!coupons && !printPose) {
            if (part == 1) {
                y = d.jawFront + 20 - y;
                z = d.jawZ + 2.8 - z;
            }
            if (part == 2) {
                y += d.jawFront;
                z += d.pcbTop + d.p.n("stencil_gap") + d.p.n("stencil_thickness");
            }
            if (part > 0) {
                double a = Math.toRadians(-angle), yy = y - d.hingeY, zz = z - d.hingeZ;
                y = d.hingeY + Math.cos(a) * yy - Math.sin(a) * zz;
                z = d.hingeZ + Math.sin(a) * yy + Math.cos(a) * zz + lift;
            }
            z += part * 16 * explode;
        } else {
            if (printPose && !coupons) {
                x += part * (d.baseWidth + 8);
            }
        }
        return new double[] { x, y, z };
    }

    private static final class Triangle {

        double[] xy = new double[6], z = new double[3];

        double depth;

        Color color;

        double alpha = 1;
    }

    private void render3D(Graphics2D g) {
        StencilPlan d = new StencilPlan(modelParameters);
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        // Fit uses stable assembly extents, so explode and flip do not reset the user's camera.
        if (coupons || printPose) {
            for (int i = 0; i < meshes.size(); i++) {
                for (double[] t : meshes.get(i).triangles) {
                    for (int k = 0; k < 9; k += 3) {
                        double[] v = world(t[k], t[k + 1], t[k + 2], i, d);
                        minX = Math.min(minX, v[0]);
                        maxX = Math.max(maxX, v[0]);
                        minY = Math.min(minY, v[1]);
                        maxY = Math.max(maxY, v[1]);
                    }
                }
            }
        } else {
            minX = d.baseLeft;
            maxX = d.baseRight;
            minY = d.baseFront;
            maxY = d.baseRear;
        }
        centerX = (minX + maxX) / 2;
        centerY = (minY + maxY) / 2;
        scale = Math.max(.05, Math.min((getWidth() - 80) / (maxX - minX + 35), (getHeight() - 100) / (maxY - minY + 60))) * zoom;
        cx = getWidth() / 2.0 + panX;
        cy = getHeight() / 2.0 + panY;
        double a = Math.toRadians(yaw), b = Math.toRadians(pitch), ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b);
        List<Triangle> draw = new ArrayList<>();
        for (int i = 0; i < meshes.size(); i++) {
            for (double[] t : meshes.get(i).triangles) {
                Triangle tri = new Triangle();
                double[][] v = new double[3][];
                for (int k = 0; k < 3; k++) {
                    v[k] = world(t[k * 3], t[k * 3 + 1], t[k * 3 + 2], i, d);
                    double x = v[k][0] - centerX, y = v[k][1] - centerY, z = v[k][2];
                    double q = sa * x + ca * y;
                    tri.xy[k * 2] = cx + (ca * x - sa * y) * scale;
                    tri.xy[k * 2 + 1] = cy - (sb * q - cb * z) * scale;
                    tri.z[k] = cb * q + sb * z;
                    tri.depth += tri.z[k] / 3;
                }
                double ux = v[1][0] - v[0][0], uy = v[1][1] - v[0][1], uz = v[1][2] - v[0][2], vx = v[2][0] - v[0][0], vy = v[2][1] - v[0][1], vz = v[2][2] - v[0][2];
                double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx, len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                double light = .6 + .4 * Math.abs((-.3 * nx - .4 * ny + .86 * nz) / Math.max(1e-12, len));
                Color color = COLORS[coupons ? 1 : i % 3];
                tri.color = new Color((int) (color.getRed() * light), (int) (color.getGreen() * light), (int) (color.getBlue() * light));
                draw.add(tri);
            }
        }
        // A depth buffer is required here: sorting whole triangles exposes underside
        // pockets through large, coplanar top faces on a concave CAD mesh.
        BufferedImage raster = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) raster.getRaster().getDataBuffer()).getData();
        Arrays.fill(pixels, getBackground().getRGB());
        double[] depth = new double[pixels.length];
        Arrays.fill(depth, Double.NEGATIVE_INFINITY);
        List<Triangle> steel = new ArrayList<>();
        if (references && !coupons && !printPose && explode < .0001) {
            for (double[] t : boxTriangles(0, 0, d.pocketFloor + d.p.n("pcb_thickness") / 2, d.p.n("pcb_length"), d.p.n("pcb_width"), d.p.n("pcb_thickness"))) {
                draw.add(project(t, 0, new Color(54, 94, 70), 1, d, ca, sa, cb, sb));
            }
            for (double[] t : boxTriangles(d.jawCenter, (d.jawFront + d.p.n("grip_depth") - d.p.n("pcb_width") / 2) / 2, d.pcbTop + d.p.n("stencil_gap") + d.p.n("stencil_thickness") / 2, d.jawWidth, d.p.n("pcb_width") + d.p.n("stencil_margin"), d.p.n("stencil_thickness"))) {
                steel.add(project(t, 3, new Color(200, 212, 216), .20, d, ca, sa, cb, sb));
            }
        }
        for (Triangle t : draw) {
            raster(t, pixels, depth, getWidth(), getHeight());
        }
        for (Triangle t : steel) {
            raster(t, pixels, depth, getWidth(), getHeight());
        }
        g.drawImage(raster, 0, 0, null);
        g.setColor(new Color(40, 60, 50));
        g.drawString(coupons ? "C0.00～C0.40 · 9 组 / 18 件 · 同板打印" : printPose ? "打印姿态 · 三件底面 Z=0" : String.format(Locale.ROOT, "装配姿态 · 翻转 %.0f° · 抬升 %.1f mm", angle, lift), 16, 23);
    }

    private Triangle project(double[] t, int part, Color color, double alpha, StencilPlan d, double ca, double sa, double cb, double sb) {
        Triangle tri = new Triangle();
        tri.color = color;
        tri.alpha = alpha;
        for (int k = 0; k < 3; k++) {
            double[] v = world(t[k * 3], t[k * 3 + 1], t[k * 3 + 2], part, d);
            double x = v[0] - centerX, y = v[1] - centerY, z = v[2], q = sa * x + ca * y;
            tri.xy[k * 2] = cx + (ca * x - sa * y) * scale;
            tri.xy[k * 2 + 1] = cy - (sb * q - cb * z) * scale;
            tri.z[k] = cb * q + sb * z;
        }
        return tri;
    }

    private static List<double[]> boxTriangles(double x, double y, double z, double w, double h, double d) {
        double[][] v = new double[8][3];
        for (int i = 0; i < 8; i++) {
            v[i] = new double[] { x + ((i & 1) == 0 ? -w : w) / 2, y + ((i & 2) == 0 ? -h : h) / 2, z + ((i & 4) == 0 ? -d : d) / 2 };
        }
        int[][] faces = { { 0, 2, 3 }, { 0, 3, 1 }, { 4, 5, 7 }, { 4, 7, 6 }, { 0, 1, 5 }, { 0, 5, 4 }, { 2, 6, 7 }, { 2, 7, 3 }, { 0, 4, 6 }, { 0, 6, 2 }, { 1, 3, 7 }, { 1, 7, 5 } };
        List<double[]> out = new ArrayList<>();
        for (int[] f : faces) {
            double[] t = new double[9];
            for (int i = 0; i < 3; i++) {
                System.arraycopy(v[f[i]], 0, t, i * 3, 3);
            }
            out.add(t);
        }
        return out;
    }

    private static void raster(Triangle t, int[] pixels, double[] depths, int width, int height) {
        double x0 = t.xy[0], y0 = t.xy[1], x1 = t.xy[2], y1 = t.xy[3], x2 = t.xy[4], y2 = t.xy[5];
        double den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(den) < 1e-10) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2)))), maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2)))), maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double a = ((y1 - y2) * (x + .5 - x2) + (x2 - x1) * (y + .5 - y2)) / den, b = ((y2 - y0) * (x + .5 - x2) + (x0 - x2) * (y + .5 - y2)) / den, c = 1 - a - b;
                if (a < -1e-9 || b < -1e-9 || c < -1e-9) {
                    continue;
                }
                double z = a * t.z[0] + b * t.z[1] + c * t.z[2];
                int index = y * width + x;
                if (z > depths[index] + (t.alpha < 1 ? 1e-7 : 0)) {
                    if (t.alpha >= 1) {
                        depths[index] = z;
                        pixels[index] = t.color.getRGB();
                    } else {
                        Color old = new Color(pixels[index]);
                        pixels[index] = new Color((int) (old.getRed() * (1 - t.alpha) + t.color.getRed() * t.alpha), (int) (old.getGreen() * (1 - t.alpha) + t.color.getGreen() * t.alpha), (int) (old.getBlue() * (1 - t.alpha) + t.color.getBlue() * t.alpha)).getRGB();
                    }
                }
            }
        }
    }
}
