/* Copyright (C) 2026 OpenPnP Pro contributors */
package org.openpnp.vision.smart;

import java.util.Objects;
import java.util.UUID;

/** Non-secret AI account/model settings. API keys are stored separately. */
public final class AiProviderProfile {
    private String id = UUID.randomUUID().toString();
    private String name = "硅基流动视觉";
    private AiProviderPreset preset = AiProviderPreset.SILICON_FLOW;
    private String baseUrl = AiProviderPreset.SILICON_FLOW.getDefaultBaseUrl();
    private String model = "";
    private int timeoutSeconds = 60;
    private String imageDetail = "high";

    public String getId() { return id; }
    public void setId(String id) { this.id = Objects.requireNonNull(id); }
    public String getName() { return name; }
    public void setName(String name) { this.name = safe(name); }
    public AiProviderPreset getPreset() { return preset; }
    public void setPreset(AiProviderPreset preset) { this.preset = Objects.requireNonNull(preset); }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = safe(baseUrl); }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = safe(model); }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = Math.max(5, Math.min(600, timeoutSeconds));
    }
    public String getImageDetail() { return imageDetail; }
    public void setImageDetail(String imageDetail) {
        this.imageDetail = "low".equals(imageDetail) || "auto".equals(imageDetail)
                ? imageDetail : "high";
    }

    public String normalizedBaseUrl() {
        String result = safe(baseUrl).trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public String endpoint(String path) {
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return normalizedBaseUrl() + path;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    @Override
    public String toString() { return name.isBlank() ? preset.toString() : name; }
}
