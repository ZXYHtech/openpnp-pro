/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.smart;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Disk-backed scheme history. Pipeline XML is kept outside machine.xml so old versions survive. */
public class SmartVisionSchemeStore {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File file;

    public SmartVisionSchemeStore(File configurationDirectory, String contextKey) {
        File directory = new File(configurationDirectory, "openpnp-pro/smart-vision-schemes");
        String safeKey = contextKey == null ? "vision"
                : contextKey.replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("-+", "-");
        file = new File(directory, safeKey + ".json");
    }

    public synchronized List<SmartVisionScheme> load() throws Exception {
        if (!file.isFile()) {
            return new ArrayList<>();
        }
        SmartVisionScheme[] schemes = gson.fromJson(new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8), SmartVisionScheme[].class);
        return schemes == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(schemes));
    }

    public synchronized void save(List<SmartVisionScheme> schemes) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.write(temporary.toPath(), gson.toJson(schemes).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        }
        catch (Exception unsupportedAtomicMove) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public File getFile() { return file; }
}
