package org.openpnp.vision.smart;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VisionParameterHelpTest {
    @Test
    void everyOriginalPropertyReceivesChineseContextualHelp() {
        assertTrue(VisionParameterHelp.describe("thresholdMin").contains("阈值"));
        assertTrue(VisionParameterHelp.describe("minScore").contains("置信度"));
        assertTrue(VisionParameterHelp.describe("vendorSpecificParameter").contains("小步调整"));
    }
}
