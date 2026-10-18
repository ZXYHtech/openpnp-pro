package org.openpnp.stencil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.UUID;
import com.google.gson.*;

final class StencilFiles {

    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    static String text(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    static void write(Path p, Object value) throws IOException {
        writeText(p, JSON.toJson(value));
    }

    static void writeText(Path p, String value) throws IOException {
        Files.createDirectories(p.toAbsolutePath().getParent());
        Path temp = p.resolveSibling(p.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temp, value.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            try {
                Files.move(temp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String hash(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) != -1) {
                    md.update(b, 0, n);
                }
            }
            StringBuilder s = new StringBuilder();
            for (byte b : md.digest()) {
                s.append(String.format("%02x", b & 255));
            }
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String safeName(String name) {
        String s = name.replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_").replaceAll("[. ]+$", "");
        if (s.length() > 48) {
            s = s.substring(0, 48);
        }
        return "型号_" + (s.isEmpty() ? "未命名" : s);
    }
}
