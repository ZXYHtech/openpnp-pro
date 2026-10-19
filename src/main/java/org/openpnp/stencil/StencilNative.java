package org.openpnp.stencil;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import com.google.gson.*;

/**
 * Verified, local native CAD runtime. Never searches PATH for Python or launches a shell.
 */
public final class StencilNative {

    private static final String ROOT = "/org/openpnp/stencil/native/windows-x86_64/";

    public static synchronized Path prepare(Path cache) throws IOException {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") || !System.getProperty("os.arch").contains("64")) {
            throw new IOException("当前安装包的钢网实体内核适用于 Windows 64 位");
        }
        Path dir = cache.resolve("occt-8.0.1-pro1");
        Files.createDirectories(dir);
        try (FileChannel channel = FileChannel.open(dir.resolve(".extract.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.lock();
            InputStream in = StencilNative.class.getResourceAsStream(ROOT + "manifest.json")) {
            if (in == null) {
                throw new IOException("安装包缺少钢网 CAD 内核，请使用完整 Pro 安装包");
            }
            JsonObject manifest = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : manifest.getAsJsonArray("files")) {
                JsonObject entry = e.getAsJsonObject();
                String name = entry.get("name").getAsString(), sha = entry.get("sha256").getAsString();
                if (!name.matches("[A-Za-z0-9_.-]+")) {
                    throw new IOException("非法内核文件名");
                }
                Path target = dir.resolve(name);
                if (Files.isRegularFile(target) && StencilFiles.hash(target).equalsIgnoreCase(sha)) {
                    continue;
                }
                Path temp = dir.resolve(name + ".partial");
                try (InputStream resource = StencilNative.class.getResourceAsStream(ROOT + name)) {
                    if (resource == null) {
                        throw new IOException("安装包内核文件缺失：" + name);
                    }
                    Files.copy(resource, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!StencilFiles.hash(temp).equalsIgnoreCase(sha)) {
                    Files.deleteIfExists(temp);
                    throw new IOException("内核文件校验失败：" + name);
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return dir.resolve("stencil-cad.exe");
    }

    private StencilNative() {
    }
}
