/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.components;

/**
 * Prevents another screen jog from using a frame captured before the preceding jog completed.
 */
final class CameraViewJogGate {
    private long generation;
    private boolean busy;
    private long waitForFrameAfter = -1;

    synchronized long tryBegin() {
        if (busy) {
            return -1;
        }
        busy = true;
        waitForFrameAfter = -1;
        return ++generation;
    }

    synchronized void motionCompleted(long token, long currentFrameSequence) {
        if (busy && token == generation) {
            waitForFrameAfter = currentFrameSequence;
        }
    }

    synchronized boolean frameReceived(long frameSequence) {
        if (busy && waitForFrameAfter >= 0 && frameSequence > waitForFrameAfter) {
            busy = false;
            waitForFrameAfter = -1;
            return true;
        }
        return false;
    }

    synchronized boolean failedOrTimedOut(long token) {
        if (busy && token == generation) {
            busy = false;
            waitForFrameAfter = -1;
            return true;
        }
        return false;
    }

    synchronized boolean isBusy() {
        return busy;
    }
}
