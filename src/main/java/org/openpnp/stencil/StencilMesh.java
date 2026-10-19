package org.openpnp.stencil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.google.gson.stream.JsonReader;

/**
 * Common mesh data for Java preview and standard OPC/3MF output. Coordinates stay in mm.
 */
public final class StencilMesh {

    public final List<double[]> triangles;

    private StencilMesh(List<double[]> t) {
        triangles = Collections.unmodifiableList(t);
    }

    public static StencilMesh read(Path p) throws IOException {
        List<double[]> triangles = new ArrayList<>();
        try (JsonReader r = new JsonReader(Files.newBufferedReader(p, StandardCharsets.UTF_8))) {
            r.beginObject();
            if (!r.nextName().equals("triangles")) {
                throw new IOException("无效网格");
            }
            r.beginArray();
            while (r.hasNext()) {
                r.beginArray();
                double[] t = new double[9];
                for (int i = 0; i < 9; i++) {
                    t[i] = r.nextDouble();
                    if (!Double.isFinite(t[i])) {
                        throw new IOException("无效网格坐标");
                    }
                }
                r.endArray();
                triangles.add(t);
                if (triangles.size() > 2000000) {
                    throw new IOException("网格过大");
                }
            }
            r.endArray();
            r.endObject();
        }
        if (triangles.isEmpty()) {
            throw new IOException("网格为空");
        }
        return new StencilMesh(triangles);
    }

    private static void entry(ZipOutputStream z, String path, String text) throws IOException {
        z.putNextEntry(new ZipEntry(path));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    public void write3mf(Path path) throws IOException {
        Map<String, Integer> indices = new LinkedHashMap<>();
        List<double[]> vertices = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        for (double[] t : triangles) {
            int[] face = new int[3];
            for (int j = 0; j < 3; j++) {
                int i = j * 3;
                String key = Math.round(t[i] * 1e7) + "," + Math.round(t[i + 1] * 1e7) + "," + Math.round(t[i + 2] * 1e7);
                Integer index = indices.get(key);
                if (index == null) {
                    index = vertices.size();
                    indices.put(key, index);
                    vertices.add(new double[] { t[i], t[i + 1], t[i + 2] });
                }
                face[j] = index;
            }
            if (face[0] == face[1] || face[1] == face[2] || face[0] == face[2]) {
                continue;
            }
            faces.add(face);
        }
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(path))) {
            entry(z, "[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/></Types>");
            entry(z, "_rels/.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/></Relationships>");
            z.putNextEntry(new ZipEntry("3D/3dmodel.model"));
            Writer w = new BufferedWriter(new OutputStreamWriter(z, StandardCharsets.UTF_8));
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><model unit=\"millimeter\" xml:lang=\"zh-CN\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources><object id=\"1\" type=\"model\"><mesh><vertices>");
            for (double[] v : vertices) {
                w.write("<vertex x=\"" + v[0] + "\" y=\"" + v[1] + "\" z=\"" + v[2] + "\"/>");
            }
            w.write("</vertices><triangles>");
            for (int[] f : faces) {
                w.write("<triangle v1=\"" + f[0] + "\" v2=\"" + f[1] + "\" v3=\"" + f[2] + "\"/>");
            }
            w.write("</triangles></mesh></object></resources><build><item objectid=\"1\"/></build></model>");
            w.flush();
            z.closeEntry();
        }
    }
}
