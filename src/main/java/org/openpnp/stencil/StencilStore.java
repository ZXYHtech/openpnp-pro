package org.openpnp.stencil;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Independent design storage; never writes machine.xml or the app center's database.
 */
public final class StencilStore {

    public static final class Preset {

        public String id, name;

        public long revision;

        public boolean deleted;

        public StencilParameters parameters;

        @Override
        public String toString() {
            return name;
        }
    }

    public static final class ReplaceRequired extends IOException {

        public final Preset existing;

        ReplaceRequired(Preset p) {
            super("同名配置已存在，请确认替换");
            existing = p;
        }
    }

    private final Path root;

    private final List<Preset> presets = new ArrayList<>();

    public StencilStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        Path file = root.resolve("presets.json");
        if (Files.exists(file)) {
            com.google.gson.JsonArray a = new com.google.gson.JsonParser().parse(StencilFiles.text(file)).getAsJsonArray();
            for (com.google.gson.JsonElement e : a) {
                com.google.gson.JsonObject o = e.getAsJsonObject();
                Preset p = new Preset();
                p.id = o.get("id").getAsString();
                p.name = o.get("name").getAsString();
                p.revision = o.get("revision").getAsLong();
                p.deleted = o.get("deleted").getAsBoolean();
                p.parameters = StencilParameters.parse(o.get("parameters").toString());
                presets.add(p);
            }
        }
    }

    public Path root() {
        return root;
    }

    public synchronized StencilParameters startup() throws IOException {
        Path p = root.resolve("startup.json");
        return Files.exists(p) ? StencilParameters.parse(StencilFiles.text(p)) : StencilParameters.factory();
    }

    public synchronized void startup(StencilParameters p) throws IOException {
        StencilFiles.write(root.resolve("startup.json"), p.envelope());
    }

    public synchronized double[] paper() throws IOException {
        Path file = root.resolve("label-paper.json");
        if (!Files.exists(file)) {
            return new double[] { 50, 30 };
        }
        com.google.gson.JsonObject o = new com.google.gson.JsonParser().parse(StencilFiles.text(file)).getAsJsonObject();
        double w = o.get("width").getAsDouble(), h = o.get("height").getAsDouble();
        validatePaper(w, h);
        return new double[] { w, h };
    }

    public synchronized void paper(double w, double h) throws IOException {
        validatePaper(w, h);
        Map<String, Double> p = new LinkedHashMap<>();
        p.put("width", w);
        p.put("height", h);
        StencilFiles.write(root.resolve("label-paper.json"), p);
    }

    private void validatePaper(double w, double h) throws IOException {
        if (!Double.isFinite(w) || !Double.isFinite(h) || w < 15 || w > 200 || h < 10 || h > 200) {
            throw new IOException("标签纸张尺寸无效");
        }
    }

    public synchronized com.google.gson.JsonObject ui() throws IOException {
        Path p = root.resolve("window.json");
        return Files.exists(p) ? new com.google.gson.JsonParser().parse(StencilFiles.text(p)).getAsJsonObject() : new com.google.gson.JsonObject();
    }

    public synchronized void ui(com.google.gson.JsonObject value) throws IOException {
        StencilFiles.write(root.resolve("window.json"), value);
    }

    public synchronized List<Preset> presets(boolean deleted) {
        List<Preset> result = new ArrayList<>();
        for (Preset p : presets) {
            if (p.deleted == deleted) {
                result.add(copy(p));
            }
        }
        return result;
    }

    private Preset copy(Preset p) {
        Preset c = new Preset();
        c.id = p.id;
        c.name = p.name;
        c.revision = p.revision;
        c.deleted = p.deleted;
        c.parameters = new StencilParameters(p.parameters.json());
        return c;
    }

    public synchronized Preset save(String name, StencilParameters p, String replaceId, long expectedRevision) throws IOException {
        name = name.trim();
        if (name.isEmpty() || name.length() > 80 || name.chars().anyMatch(c -> c < 32)) {
            throw new IOException("配置名称需为 1～80 个普通字符");
        }
        Preset current = null;
        for (Preset a : presets) {
            if (!a.deleted && a.name.equals(name)) {
                current = a;
            }
        }
        if (current != null) {
            if (replaceId == null) {
                throw new ReplaceRequired(copy(current));
            }
            if (!current.id.equals(replaceId) || current.revision != expectedRevision) {
                throw new IOException("原配置已变化，请重新保存并确认");
            }
            StencilFiles.write(root.resolve("backups").resolve(current.id + "-" + current.revision + ".json"), record(current));
        } else {
            if (replaceId != null) {
                throw new IOException("原配置已变化，请重新保存");
            }
        }
        Preset value = new Preset();
        value.id = current == null ? UUID.randomUUID().toString() : current.id;
        value.name = name;
        value.parameters = p;
        value.revision = current == null ? 1 : current.revision + 1;
        List<Preset> next = new ArrayList<>(presets);
        if (current != null) {
            next.remove(current);
        }
        next.add(value);
        persist(next);
        presets.clear();
        presets.addAll(next);
        return copy(value);
    }

    public synchronized void deleted(String id, boolean value) throws IOException {
        List<Preset> next = new ArrayList<>();
        boolean found = false;
        for (Preset p : presets) {
            Preset c = copy(p);
            if (c.id.equals(id)) {
                found = true;
                if (!value) {
                    for (Preset a : presets) {
                        if (!a.deleted && a.name.equals(c.name) && !a.id.equals(c.id)) {
                            throw new IOException("已有同名配置，无法撤销删除");
                        }
                    }
                }
                c.deleted = value;
                c.revision++;
            }
            next.add(c);
        }
        if (!found) {
            throw new IOException("配置不存在");
        }
        persist(next);
        presets.clear();
        presets.addAll(next);
    }

    private Map<String, Object> record(Preset p) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", p.id);
        o.put("name", p.name);
        o.put("revision", p.revision);
        o.put("deleted", p.deleted);
        o.put("parameters", p.parameters.json());
        return o;
    }

    private void persist(List<Preset> values) throws IOException {
        List<Object> rows = new ArrayList<>();
        for (Preset p : values) {
            rows.add(record(p));
        }
        StencilFiles.write(root.resolve("presets.json"), rows);
    }
}
