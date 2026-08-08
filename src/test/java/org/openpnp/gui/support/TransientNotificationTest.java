package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TransientNotificationTest {
    @Test
    void recognizesMotionLimitFailuresThroughNestedCauses() {
        Exception failure = new Exception("move rejected",
                new Exception("Can't move X to 501, higher than soft limit 500."));

        assertTrue(TransientNotification.isMotionLimitFailure(failure));
        assertTrue(TransientNotification.isTransientMotionFailure(
                new Exception("Can't move y to -8.190mm, lower than soft limit 0.000mm.")));
        assertTrue(TransientNotification.isMotionLimitFailure(
                new Exception("目标超出可达范围")));
    }

    @Test
    void doesNotHideOtherMachineFailures() {
        assertFalse(TransientNotification.isMotionLimitFailure(
                new Exception("Serial port disconnected")));
        assertFalse(TransientNotification.isMotionLimitFailure(
                new Exception("Nozzle would potentially crash into board PCB1")));
    }

    @Test
    void recognizesDisabledAndNotHomedJogFailures() {
        assertTrue(TransientNotification.isTransientMotionFailure(
                new Exception("Machine is not enabled")));
        assertTrue(TransientNotification.isTransientMotionFailure(
                new Exception("Machine has not been homed")));
        assertFalse(TransientNotification.isTransientMotionFailure(
                new Exception("Serial port disconnected")));
        assertEquals("移动未执行：机器尚未启动或未就绪，请先启用机器。",
                TransientNotification.motionFailureMessage(
                        new Exception("Machine is not enabled")));
        assertEquals("移动未执行：机器尚未回零，请先完成 Home。",
                TransientNotification.motionFailureMessage(
                        new Exception("Machine has not been homed")));
    }
}
