package org.openpnp.gui.importer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure, size-bounded KiCad S-expression footprint parser. */
public final class KicadFootprintParser {
    public static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;

    public static final class ParsedPad {
        public String name, type, shape;
        public double x, y, rotation, width, height, roundness;
        public boolean topCopper, approximated;
    }
    public static final class Result {
        public String name, description, tags, bodySource;
        public double bodyMinX, bodyMinY, bodyMaxX, bodyMaxY;
        public boolean hasBodyBounds;
        public final List<ParsedPad> pads = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public File sourceFile;
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }

    static final class Node {
        final String atom;
        final List<Node> children;
        Node(String atom) { this.atom = atom;
        this.children = null; }
        Node(List<Node> children) { this.atom = null;
        this.children = children; }
        boolean list() { return children != null; }
        String head() { return list() && !children.isEmpty() ? children.get(0).atom : null; }
    }

    public Result parse(File file) throws IOException {
        if (file == null || !file.isFile()) { throw new IOException("不是可读取的封装文件"); }
        if (file.length() > MAX_FILE_BYTES) { throw new IOException("文件超过 8 MB 安全上限"); }
        Result result = parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        result.sourceFile = file;
        if (result.name == null || result.name.trim().isEmpty())
            { result.name = file.getName().replaceFirst("(?i)\\.kicad_mod$", ""); }
        return result;
    }

    public Result parse(String source) throws IOException {
        if (source == null) { throw new IOException("输入为空"); }
        Parser parser = new Parser(source);
        List<Node> roots = parser.parseAll();
        Node footprint = null;
        for (Node root : roots) { if ("footprint".equals(root.head()) || "module".equals(root.head())) {
            footprint = root;
            break;
        } }
        if (footprint == null) { throw new IOException("未找到 (footprint ...) 或 (module ...) 根节点"); }
        Result result = new Result();
        result.name = atom(footprint, 1, "");
        result.description = firstValue(footprint, "descr");
        result.tags = firstValue(footprint, "tags");
        int unsupported = 0, throughHole = 0, backOnly = 0;
        for (Node child : footprint.children) {
            if (!child.list()) { continue; }
            if ("pad".equals(child.head())) {
                ParsedPad pad = parsePad(child);
                if (!"smd".equalsIgnoreCase(pad.type)) { throughHole++;
                continue; }
                if (!pad.topCopper) { backOnly++;
                continue; }
                if (!isSupported(pad.shape)) {
                    unsupported++;
                    pad.approximated = true;
                    pad.shape = "rect";
                }
                result.pads.add(pad);
            }
        }
        Bounds fab = outlineBounds(footprint, "F.Fab");
        Bounds courtyard = outlineBounds(footprint, "F.CrtYd");
        Bounds chosen = fab.valid ? fab : courtyard.valid ? courtyard : padBounds(result.pads);
        if (chosen.valid) {
            result.hasBodyBounds = true;
            result.bodyMinX = chosen.minX;
            result.bodyMinY = chosen.minY;
            result.bodyMaxX = chosen.maxX;
            result.bodyMaxY = chosen.maxY;
            result.bodySource = fab.valid ? "F.Fab" : courtyard.valid ? "F.CrtYd" : "焊盘包围盒";
        }
        if (unsupported > 0) { result.warnings.add(unsupported + " 个 custom/trapezoid/chamfer 焊盘已按外接矩形近似"); }
        if (throughHole > 0) { result.warnings.add(throughHole + " 个通孔/机械焊盘未用于底部视觉轮廓"); }
        if (backOnly > 0) { result.warnings.add(backOnly + " 个背面专用焊盘已跳过"); }
        if (result.pads.isEmpty()) { result.warnings.add("没有可用的顶层 SMD 铜焊盘"); }
        if (!fab.valid && !courtyard.valid) { result.warnings.add("未找到 F.Fab/F.CrtYd，本体尺寸由焊盘包围盒推导"); }
        return result;
    }

    private static ParsedPad parsePad(Node node) {
        ParsedPad p = new ParsedPad();
        p.name = atom(node, 1, "");
        p.type = atom(node, 2, "");
        p.shape = atom(node, 3, "rect");
        Node at = child(node, "at"), size = child(node, "size"), layers = child(node, "layers");
        p.x = number(at, 1);
        p.y = number(at, 2);
        p.rotation = number(at, 3);
        p.width = Math.abs(number(size, 1));
        p.height = Math.abs(number(size, 2));
        Node ratio = child(node, "roundrect_rratio");
        p.roundness = "circle".equals(p.shape) || "oval".equals(p.shape) ? 100
                : "roundrect".equals(p.shape) ? number(ratio, 1) * 100 : 0;
        p.topCopper = layers != null && layers.children.stream().skip(1)
                .anyMatch(n -> n.atom != null && (n.atom.contains("F.Cu") || n.atom.contains("*.Cu")));
        return p;
    }

    private static boolean isSupported(String shape) {
        return "rect".equals(shape) || "roundrect".equals(shape)
                || "circle".equals(shape) || "oval".equals(shape);
    }
    private static String firstValue(Node root, String head) { return atom(child(root, head), 1, null); }
    private static Node child(Node node, String head) {
        if (node == null || !node.list()) { return null; }
        for (Node n : node.children) { if (head.equals(n.head())) { return n; } }
        return null;
    }
    private static String atom(Node node, int index, String fallback) {
        return node != null && node.list() && node.children.size() > index
                && node.children.get(index).atom != null ? node.children.get(index).atom : fallback;
    }
    private static double number(Node node, int index) {
        try { return Double.parseDouble(atom(node, index, "0")); }
        catch (NumberFormatException e) { return 0; }
    }

    private static final class Bounds {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        boolean valid;
        void add(double x, double y) { minX=Math.min(minX,x);
        minY=Math.min(minY,y);
        maxX=Math.max(maxX,x);
        maxY=Math.max(maxY,y);
        valid=true; }
    }
    private static Bounds outlineBounds(Node root, String layer) {
        Bounds b = new Bounds();
        for (Node n : root.children) {
            if (!n.list() || !("fp_line".equals(n.head()) || "fp_rect".equals(n.head()))) { continue; }
            Node layerNode = child(n, "layer");
            if (layerNode == null || !layer.equalsIgnoreCase(atom(layerNode, 1, ""))) { continue; }
            Node start = child(n, "start"), end = child(n, "end");
            if (start != null) { b.add(number(start,1), number(start,2)); }
            if (end != null) { b.add(number(end,1), number(end,2)); }
        }
        return b;
    }
    private static Bounds padBounds(List<ParsedPad> pads) {
        Bounds b = new Bounds();
        for (ParsedPad p : pads) { b.add(p.x-p.width/2,p.y-p.height/2);
        b.add(p.x+p.width/2,p.y+p.height/2); }
        return b;
    }

    static final class Parser {
        final String text;
        int pos;
        Parser(String text) { this.text = text; }
        List<Node> parseAll() throws IOException {
            List<Node> roots = new ArrayList<>();
            skip();
            while (pos < text.length()) { roots.add(parseNode());
            skip(); }
            return roots;
        }
        Node parseNode() throws IOException {
            skip();
            if (pos >= text.length()) { throw error("意外到达文件末尾"); }
            if (text.charAt(pos) == '(') {
                pos++;
                List<Node> children = new ArrayList<>();
                skip();
                while (pos < text.length() && text.charAt(pos) != ')') { children.add(parseNode());
                skip(); }
                if (pos >= text.length()) { throw error("括号未闭合"); }
                pos++;
                return new Node(children);
            }
            if (text.charAt(pos) == ')') { throw error("多余的右括号"); }
            return new Node(readAtom());
        }
        String readAtom() throws IOException {
            if (text.charAt(pos) == '"') {
                pos++;
                StringBuilder b = new StringBuilder();
                boolean escape=false;
                while (pos < text.length()) {
                    char c=text.charAt(pos++);
                    if (escape) { b.append(c=='n'?'\n':c=='t'?'\t':c);
                    escape=false; }
                    else { if (c=='\\') { escape=true; }
                    else { if (c=='"') { return b.toString(); }
                    else { b.append(c); } } }
                }
                throw error("字符串未闭合");
            }
            int start=pos;
            while (pos<text.length()) { char c=text.charAt(pos);
            if (Character.isWhitespace(c)||c=='('||c==')'||c==';') { break; }
            pos++; }
            if (start==pos) { throw error("无法解析字符"); }
            return text.substring(start,pos);
        }
        void skip() {
            while (pos<text.length()) {
                char c=text.charAt(pos);
                if (Character.isWhitespace(c)) { pos++;
                continue; }
                if (c==';') {
                    while(pos<text.length()&&text.charAt(pos)!='\n') { pos++; }
                    continue;
                }
                break;
            }
        }
        IOException error(String message) {
            int line=1,column=1;
            for(int i=0;i<pos&&i<text.length();i++){if(text.charAt(i)=='\n'){line++;
            column=1;}
            else { column++; }}
            return new IOException(message+"（第 "+line+" 行，第 "+column+" 列）");
        }
    }
}
