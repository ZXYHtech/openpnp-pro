/* Copyright (C) 2026 OpenPnP Pro contributors */
package org.openpnp.vision.smart;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Selects a likely vision model from the provider's live /models response. */
public final class AiModelRecommender {
    private AiModelRecommender() {
    }

    public static Optional<String> recommend(List<String> modelIds) {
        if (modelIds == null) {
            return Optional.empty();
        }
        return modelIds.stream().filter(AiModelRecommender::looksLikeVisionModel)
                .max(Comparator.comparingInt(AiModelRecommender::score));
    }

    public static boolean looksLikeVisionModel(String id) {
        String value = lower(id);
        return value.contains("qwen3-vl") || value.contains("qwen2-vl")
                || value.contains("vision") || value.contains("omni")
                || value.contains("glm-4v") || value.contains("pixtral")
                || value.contains("deepseek-vl") || value.contains("ocr")
                || value.contains("gpt-4o") || value.contains("gpt-5.6");
    }

    private static int score(String id) {
        String value = lower(id);
        int score = 0;
        if (value.contains("qwen3-vl")) { score += 100; }
        if (value.contains("qwen3-omni")) { score += 95; }
        if (value.contains("glm-4.5v") || value.contains("glm-4.6v")) { score += 90; }
        if (value.contains("deepseek-vl")) { score += 78; }
        if (value.contains("ocr")) { score += 35; }
        if (value.contains("instruct")) { score += 8; }
        if (value.contains("pro") || value.contains("max")) { score += 5; }
        return score;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
