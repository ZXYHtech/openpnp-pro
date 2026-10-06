package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JButton;

import org.junit.jupiter.api.Test;
import org.openpnp.gui.support.ExperimentalGuide.Step;

class ExperimentalGuideTest {
    @Test
    void completionTracksLiveControlStateWithoutRunningTheAction() {
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean actionRan = new AtomicBoolean();
        JButton target = new JButton();

        Step step = new Step("测试步骤", "按说明操作", () -> target,
                completed::get, "安全界面动作", () -> actionRan.set(true));

        assertFalse(step.isComplete());
        assertFalse(actionRan.get());
        assertTrue(step.hasSafeAction());

        completed.set(true);
        assertTrue(step.isComplete());
        assertFalse(actionRan.get());

        step.runSafeAction();
        assertTrue(actionRan.get());
    }

    @Test
    void actionLabelAndCallbackMustAlwaysBePaired() {
        JButton target = new JButton();
        assertThrows(IllegalArgumentException.class,
                () -> new Step("无效步骤", "说明", () -> target, () -> true,
                        "有按钮但无动作", null));
        assertThrows(IllegalArgumentException.class,
                () -> new Step("无效步骤", "说明", () -> target, () -> true,
                        null, () -> { }));
    }
}
