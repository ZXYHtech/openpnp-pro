/* Copyright (C) 2026 OpenPnP Pro contributors */
package org.openpnp.vision.smart;

/** Provider presets all use the OpenAI-compatible chat-completions shape. */
public enum AiProviderPreset {
    SILICON_FLOW("硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1"),
    OPENAI_COMPATIBLE("OpenAI 兼容 API", "https://api.openai.com/v1"),
    LOCAL_COMPATIBLE("本地 / 局域网兼容服务", "http://127.0.0.1:11434/v1"),
    CUSTOM("自定义兼容服务", "");

    private final String displayName;
    private final String defaultBaseUrl;

    AiProviderPreset(String displayName, String defaultBaseUrl) {
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    public String getDefaultBaseUrl() { return defaultBaseUrl; }

    @Override
    public String toString() { return displayName; }
}
