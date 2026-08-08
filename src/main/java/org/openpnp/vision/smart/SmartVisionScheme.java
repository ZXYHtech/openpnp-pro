/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.smart;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** A complete, restorable smart-vision pipeline version. */
public class SmartVisionScheme {
    private String id = UUID.randomUUID().toString();
    private String name;
    private String description;
    private String template;
    private long updatedAt = System.currentTimeMillis();
    private boolean active;
    private double confidence = 0.85;
    private boolean masksPreserved = true;
    private String pipelineXml;

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }
    public long getUpdatedAt() { return updatedAt; }
    public void touch() { updatedAt = System.currentTimeMillis(); }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public boolean isMasksPreserved() { return masksPreserved; }
    public void setMasksPreserved(boolean masksPreserved) { this.masksPreserved = masksPreserved; }
    public String getPipelineXml() { return pipelineXml; }
    public void setPipelineXml(String pipelineXml) { this.pipelineXml = pipelineXml; }

    public String getUpdatedDisplay() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(updatedAt));
    }

    @Override
    public String toString() {
        return (active ? "● " : "○ ") + (name == null ? "未命名方案" : name)
                + " · " + getUpdatedDisplay();
    }
}
