/* Copyright (C) 2026 OpenPnP Pro contributors */
package org.openpnp.vision.smart;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minimal OpenAI-compatible vision client. This keeps startup light on older PCs
 * and supports SiliconFlow without binding OpenPnP to a vendor SDK.
 */
public final class AiVisionClient {
    private static final Gson GSON = new Gson();
    private static final int MAX_IMAGE_SIDE = 1280;

    public List<String> listModels(AiProviderProfile profile, String apiKey) throws IOException {
        validate(profile, apiKey, false);
        HttpURLConnection connection = open(profile.endpoint("/models"), "GET", profile, apiKey);
        String body = readResponse(connection, apiKey);
        JsonElement root = new JsonParser().parse(body);
        JsonArray data = root.getAsJsonObject().getAsJsonArray("data");
        if (data == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement item : data) {
            if (item.isJsonObject() && item.getAsJsonObject().has("id")) {
                result.add(item.getAsJsonObject().get("id").getAsString());
            }
        }
        Collections.sort(result);
        return result;
    }

    public String analyzeImage(AiProviderProfile profile, String apiKey,
            BufferedImage image, String userPrompt) throws IOException {
        validate(profile, apiKey, true);
        if (image == null) {
            throw new IllegalArgumentException("没有可发送的框选图像");
        }
        String request = buildChatRequest(profile, image, userPrompt);
        HttpURLConnection connection = open(profile.endpoint("/chat/completions"), "POST",
                profile, apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(request.getBytes(StandardCharsets.UTF_8));
        }
        String body = readResponse(connection, apiKey);
        JsonObject root = new JsonParser().parse(body).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            throw new IOException("AI 服务没有返回 choices");
        }
        JsonElement content = choices.get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content");
        if (content == null) {
            throw new IOException("AI 服务没有返回识别建议内容");
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) {
            StringBuilder combined = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    if (combined.length() > 0) {
                        combined.append('\n');
                    }
                    combined.append(part.getAsJsonObject().get("text").getAsString());
                }
            }
            if (combined.length() > 0) {
                return combined.toString();
            }
        }
        return content.toString();
    }

    public static String buildChatRequest(AiProviderProfile profile, BufferedImage image,
            String userPrompt) throws IOException {
        BufferedImage bounded = boundImage(image);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(bounded, "jpg", bytes)) {
            throw new IOException("无法编码框选图像");
        }
        String dataUrl = "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(bytes.toByteArray());

        JsonObject root = new JsonObject();
        root.addProperty("model", profile.getModel());
        root.addProperty("temperature", 0.1);
        root.addProperty("max_tokens", 1800);
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", "你是 OpenPnP 工业视觉方案助手。只分析图像和生成候选参数，"
                + "不得声称已达到未经验证的准确率，不得要求或触发机器运动。输出中文，明确区分观察、推断和待验证项。"
                + "优先推荐 OpenPnP 现有 CvPipeline 阶段和可解释的传统视觉方法；AI 只作辅助。");
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", userPrompt == null ? "分析该目标并给出视觉方案。" : userPrompt);
        content.add(text);
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUrl);
        imageUrl.addProperty("detail", profile.getImageDetail());
        imagePart.add("image_url", imageUrl);
        content.add(imagePart);
        user.add("content", content);
        messages.add(user);
        root.add("messages", messages);
        return GSON.toJson(root);
    }

    public static String redact(String text, String apiKey) {
        String result = text == null ? "" : text;
        if (apiKey != null && !apiKey.isBlank()) {
            result = result.replace(apiKey, "***");
        }
        return result.replaceAll("(?i)bearer\\s+[a-z0-9._\\-]{8,}", "Bearer ***")
                .replaceAll("sk-[a-zA-Z0-9_\\-]{8,}", "sk-***");
    }

    private static void validate(AiProviderProfile profile, String apiKey, boolean requireModel) {
        if (profile == null) {
            throw new IllegalArgumentException("未选择 AI 账户");
        }
        if (profile.normalizedBaseUrl().isBlank()) {
            throw new IllegalArgumentException("API 地址不能为空");
        }
        URI uri = URI.create(profile.normalizedBaseUrl());
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("API 地址只支持 HTTP 或 HTTPS");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API 密钥不能为空");
        }
        if (requireModel && profile.getModel().isBlank()) {
            throw new IllegalArgumentException("模型不能为空");
        }
    }

    private static HttpURLConnection open(String endpoint, String method,
            AiProviderProfile profile, String apiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setConnectTimeout(profile.getTimeoutSeconds() * 1000);
        connection.setReadTimeout(profile.getTimeoutSeconds() * 1000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        connection.setRequestProperty("User-Agent", "OpenPnP-Pro-SmartVision/1");
        return connection;
    }

    private static String readResponse(HttpURLConnection connection, String apiKey) throws IOException {
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String body = read(stream);
        if (code < 200 || code >= 300) {
            String safe = redact(body, apiKey).replace('\n', ' ').replace('\r', ' ');
            if (safe.length() > 320) {
                safe = safe.substring(0, 320) + "…";
            }
            throw new IOException("AI API HTTP " + code + (safe.isBlank() ? "" : "：" + safe));
        }
        return body;
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }
        }
        return result.toString();
    }

    private static BufferedImage boundImage(BufferedImage source) {
        int max = Math.max(source.getWidth(), source.getHeight());
        if (max <= MAX_IMAGE_SIDE) {
            return source;
        }
        double scale = MAX_IMAGE_SIDE / (double) max;
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }
}
