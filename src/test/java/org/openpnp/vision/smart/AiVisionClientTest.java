package org.openpnp.vision.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class AiVisionClientTest {
    @Test
    void siliconFlowPresetUsesOfficialOpenAiCompatibleEndpoints() {
        AiProviderProfile profile = new AiProviderProfile();

        assertEquals("https://api.siliconflow.cn/v1", profile.normalizedBaseUrl());
        assertEquals("https://api.siliconflow.cn/v1/models", profile.endpoint("models"));
        assertEquals("https://api.siliconflow.cn/v1/chat/completions",
                profile.endpoint("/chat/completions"));
    }

    @Test
    void visionRequestContainsOnlyTheRoiAndNeverContainsTheApiKey() throws Exception {
        AiProviderProfile profile = new AiProviderProfile();
        profile.setModel("provider/vision-model");
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);

        String request = AiVisionClient.buildChatRequest(profile, image, "检查圆形目标");
        JsonObject root = new JsonParser().parse(request).getAsJsonObject();
        JsonArray messages = root.getAsJsonArray("messages");
        JsonArray content = messages.get(1).getAsJsonObject().getAsJsonArray("content");
        String dataUrl = content.get(1).getAsJsonObject().getAsJsonObject("image_url")
                .get("url").getAsString();

        assertEquals("provider/vision-model", root.get("model").getAsString());
        assertTrue(dataUrl.startsWith("data:image/jpeg;base64,"));
        assertFalse(request.contains("sk-test-secret"));
    }

    @Test
    void recommendationsComeFromTheLiveProviderList() {
        String recommended = AiModelRecommender.recommend(Arrays.asList(
                "text-only", "vendor/Qwen2-VL", "vendor/Qwen3-VL-Instruct"))
                .orElseThrow();

        assertEquals("vendor/Qwen3-VL-Instruct", recommended);
    }

    @Test
    void credentialsAreMaskedAndRedacted() {
        String secret = "sk-example-secret-123456";
        assertFalse(AiProfileStore.maskedKey(secret).contains("example-secret"));
        assertFalse(AiVisionClient.redact("Authorization: Bearer " + secret, secret)
                .contains(secret));
    }
}
