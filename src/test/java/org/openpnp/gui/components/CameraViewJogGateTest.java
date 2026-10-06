package org.openpnp.gui.components;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class CameraViewJogGateTest {
    @Test
    public void requiresMotionCompletionAndANewerFrame() {
        CameraViewJogGate gate = new CameraViewJogGate();
        long token = gate.tryBegin();
        assertTrue(token > 0);
        assertTrue(gate.isBusy());
        assertTrue(gate.tryBegin() < 0);

        gate.frameReceived(10);
        assertTrue(gate.isBusy());
        gate.motionCompleted(token, 10);
        gate.frameReceived(10);
        assertTrue(gate.isBusy());
        gate.frameReceived(11);
        assertFalse(gate.isBusy());
    }

    @Test
    public void staleCompletionCannotReleaseANewerJog() {
        CameraViewJogGate gate = new CameraViewJogGate();
        long first = gate.tryBegin();
        gate.failedOrTimedOut(first);
        long second = gate.tryBegin();
        gate.failedOrTimedOut(first);
        assertTrue(gate.isBusy());
        gate.failedOrTimedOut(second);
        assertFalse(gate.isBusy());
    }
}
