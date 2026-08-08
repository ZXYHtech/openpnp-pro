/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.pipeline.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.openpnp.vision.pipeline.CvPipeline;

/**
 * UTF-8 file import/export for the same XML representation used by the
 * existing pipeline clipboard actions.
 */
public final class CvPipelineFileIo {
    public static final String DEFAULT_EXTENSION = ".xml";
    public static final long MAX_IMPORT_BYTES = 8L * 1024L * 1024L;

    private CvPipelineFileIo() {
    }

    public static CvPipeline read(File file) throws Exception {
        if (file == null || !file.isFile()) {
            throw new IOException("Pipeline file does not exist: " + file);
        }
        long size = Files.size(file.toPath());
        if (size <= 0) {
            throw new IOException("Pipeline file is empty.");
        }
        if (size > MAX_IMPORT_BYTES) {
            throw new IOException("Pipeline file is larger than 8 MB.");
        }
        String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        try {
            return new CvPipeline(xml);
        }
        catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        }
        catch (Error parseError) {
            // The legacy Simple XML wrapper used by CvPipeline reports some
            // malformed XML inputs as Error. Convert only these non-fatal
            // parser failures so Swing callers can show a normal error box.
            throw new IOException("Invalid OpenPnP vision pipeline XML.", parseError);
        }
    }

    public static void replace(CvPipeline target, File file) throws Exception {
        try (CvPipeline imported = read(file)) {
            target.fromXmlString(imported.toXmlString());
        }
    }

    public static File normalizeExportFile(File file) {
        if (file == null) {
            return null;
        }
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(DEFAULT_EXTENSION)) {
            return file;
        }
        return new File(file.getParentFile(), file.getName() + DEFAULT_EXTENSION);
    }

    public static void write(CvPipeline pipeline, File file) throws Exception {
        if (pipeline == null) {
            throw new IllegalArgumentException("Pipeline is not available.");
        }
        File normalized = normalizeExportFile(file);
        if (normalized == null) {
            throw new IllegalArgumentException("Pipeline file is not selected.");
        }
        Files.write(normalized.toPath(), pipeline.toXmlString().getBytes(StandardCharsets.UTF_8));
    }

    /** Write an already versioned Pipeline XML snapshot without requiring live camera properties. */
    public static void writeXml(String pipelineXml, File file) throws Exception {
        if (pipelineXml == null || pipelineXml.trim().isEmpty()) {
            throw new IllegalArgumentException("Pipeline XML is empty.");
        }
        // Validate before touching the destination. Offline smart-vision
        // snapshots deliberately retain current stage values and do not need a
        // camera-dependent parameter reset here.
        try (CvPipeline ignored = new CvPipeline(pipelineXml)) {
        }
        catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
            throw fatal;
        }
        catch (Error parseError) {
            throw new IOException("Invalid OpenPnP vision pipeline XML.", parseError);
        }
        File normalized = normalizeExportFile(file);
        if (normalized == null) {
            throw new IllegalArgumentException("Pipeline file is not selected.");
        }
        Files.write(normalized.toPath(), pipelineXml.getBytes(StandardCharsets.UTF_8));
    }
}
