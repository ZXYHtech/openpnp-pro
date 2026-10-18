package org.openpnp.stencil;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.print.*;
import java.util.*;
import java.util.List;
import javax.print.*;

/**
 * Paper labels use physical dimensions; printing always goes through the system dialog.
 */
public final class StencilLabel {

    public static BufferedImage image(StencilParameters p, double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width < 15 || width > 200 || height < 10 || height > 200) {
            throw new IllegalArgumentException("纸张范围：宽 15～200 mm，高 10～200 mm");
        }
        double scale = 203 / 25.4;
        BufferedImage out = new BufferedImage((int) Math.round(width * scale), (int) Math.round(height * scale), BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setColor(Color.BLACK);
        String[] text = { p.s("model").isEmpty() ? "未命名钢网夹具" : p.s("model"), "PCB " + p.n("pcb_length") + " × " + p.n("pcb_width") + " × " + p.n("pcb_thickness") + " mm", "夹口距离 " + p.n("top_distance") + " · 夹入 " + p.n("grip_depth") + " mm", "M2.5×" + (int) p.n("screw_length") + " · " + (int) p.n("screw_count") + " 颗螺钉", "钢网夹具 · 三件配套" };
        int margin = (int) (2 * scale), available = out.getWidth() - 2 * margin;
        List<String> lines = new ArrayList<>();
        int fontSize = 0;
        for (int size = (int) (Math.min(3.2, height * .115) * scale); size >= 5; size--) {
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, size));
            FontMetrics fm = g.getFontMetrics();
            lines.clear();
            for (String t : text) {
                StringBuilder line = new StringBuilder();
                for (char c : t.toCharArray()) {
                    if (line.length() > 0 && fm.stringWidth(line.toString() + c) > available) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    line.append(c);
                }
                lines.add(line.toString());
            }
            if (lines.size() * (fm.getHeight() + 2) <= out.getHeight() - 2 * margin) {
                fontSize = size;
                break;
            }
        }
        if (fontSize == 0) {
            g.dispose();
            throw new IllegalArgumentException("标签内容放不下，请增大纸张");
        }
        FontMetrics fm = g.getFontMetrics();
        int y = Math.max(margin, (out.getHeight() - lines.size() * (fm.getHeight() + 2)) / 2) + fm.getAscent();
        for (String line : lines) {
            g.drawString(line, margin, y);
            y += fm.getHeight() + 2;
        }
        g.dispose();
        return out;
    }

    public static void print(StencilParameters p, double width, double height, Component parent) throws PrinterException {
        BufferedImage image = image(p, width, height);
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("钢网夹具 · " + p.s("model"));
        for (PrintService service : PrintServiceLookup.lookupPrintServices(null, null)) {
            if (service.getName().toUpperCase(Locale.ROOT).contains("GP1424")) {
                job.setPrintService(service);
                break;
            }
        }
        Paper paper = new Paper();
        paper.setSize(width * 72 / 25.4, height * 72 / 25.4);
        paper.setImageableArea(0, 0, paper.getWidth(), paper.getHeight());
        PageFormat format = new PageFormat();
        format.setPaper(paper);
        job.setPrintable((graphics, page, index) -> {
            if (index != 0) {
                return Printable.NO_SUCH_PAGE;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(page.getImageableX(), page.getImageableY());
            g.scale(width * 72 / 25.4 / image.getWidth(), height * 72 / 25.4 / image.getHeight());
            g.drawImage(image, 0, 0, null);
            g.dispose();
            return Printable.PAGE_EXISTS;
        }, format);
        if (job.printDialog()) {
            job.print();
        }
    }

    private StencilLabel() {
    }
}
