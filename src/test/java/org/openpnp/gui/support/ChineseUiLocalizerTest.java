package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class ChineseUiLocalizerTest {
    @Test
    public void translatesLegacyCaptionsButPreservesTechnicalAxisNames() {
        assertEquals("旋转角度", ChineseUiLocalizer.translateText("Rotation"));
        assertEquals("编带内旋转角度", ChineseUiLocalizer.translateText("Rotation in Tape"));
        assertEquals("错误", ChineseUiLocalizer.translateText("Error"));
        assertEquals("X", ChineseUiLocalizer.translateText("X"));
        assertEquals("Y", ChineseUiLocalizer.translateText("Y"));
        assertEquals("ID", ChineseUiLocalizer.translateText("ID"));
    }

    @Test
    public void translatesDynamicVersionAndErrorPrefixes() {
        assertEquals("版本：2.6", ChineseUiLocalizer.translateText("Version: 2.6"));
        assertEquals("错误：Camera not found",
                ChineseUiLocalizer.translateText("Error: Camera not found"));
    }

    @Test
    public void translatesDynamicIssueAndSolutionSentences() {
        assertEquals("相机 尚未配置. 请配置 相机 位置.",
                ChineseUiLocalizer.translateIssueText(
                        "The camera is not configured. Please configure camera location."));
        assertEquals("待处理", ChineseUiLocalizer.translateSeverityOrState("Open"));
    }
}
