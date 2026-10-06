/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.smart;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Shared scheme state changes used when copying a smart scheme to a native vision channel. */
public final class SmartVisionSchemeTransfer {
    private SmartVisionSchemeTransfer() {
    }

    public static SmartVisionScheme createAutomaticBackup(String pipelineXml,
            String channelName) {
        if (pipelineXml == null || pipelineXml.trim().isEmpty()) {
            throw new IllegalArgumentException("基准视觉 Pipeline 为空，无法建立备份。");
        }
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date());
        SmartVisionScheme backup = new SmartVisionScheme();
        backup.setName("基准视觉自动备份 · " + timestamp);
        backup.setDescription((channelName == null ? "视觉" : channelName)
                + "通道被智能方案覆盖前自动保存，可随时重新复制恢复。");
        backup.setTemplate("基准视觉自动备份");
        backup.setConfidence(0.85);
        backup.setMasksPreserved(true);
        backup.setPipelineXml(pipelineXml);
        backup.setActive(false);
        return backup;
    }

    public static void activateExclusive(List<SmartVisionScheme> schemes,
            SmartVisionScheme selected) {
        if (schemes == null || selected == null || !schemes.contains(selected)) {
            throw new IllegalArgumentException("要复制的识别方案不在当前列表中。");
        }
        for (SmartVisionScheme scheme : schemes) {
            scheme.setActive(scheme == selected);
        }
        selected.touch();
    }
}
