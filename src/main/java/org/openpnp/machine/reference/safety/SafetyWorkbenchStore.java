/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.openpnp.model.Configuration;
import org.pmw.tinylog.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Lazy JSON persistence for the safety workbench. The machine configuration is
 * deliberately not modified until the operator explicitly saves the model.
 */
public final class SafetyWorkbenchStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SafetyWorkbenchModel current;

    private SafetyWorkbenchStore() {
    }

    public static synchronized SafetyWorkbenchModel get() {
        if (current == null) {
            current = load();
        }
        return current;
    }

    public static synchronized SafetyWorkbenchModel reload() {
        current = load();
        return current;
    }

    public static synchronized void save(SafetyWorkbenchModel model) throws Exception {
        File file = getFile();
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File temporary = new File(parent, file.getName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(model, writer);
        }
        try {
            Files.move(temporary.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        catch (Exception notAtomic) {
            Files.move(temporary.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        current = model;
    }

    public static File getFile() {
        return new File(Configuration.get().getConfigurationDirectory(),
                "pro-safety-workbench.json");
    }

    private static SafetyWorkbenchModel load() {
        File file = getFile();
        if (!file.isFile()) {
            return SafetyWorkbenchModel.createDefault();
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            SafetyWorkbenchModel model = GSON.fromJson(reader, SafetyWorkbenchModel.class);
            return model == null ? SafetyWorkbenchModel.createDefault() : model;
        }
        catch (Exception exception) {
            Logger.warn(exception, "Unable to load OpenPnP Pro safety workbench {}",
                    file.getAbsolutePath());
            return SafetyWorkbenchModel.createDefault();
        }
    }
}
