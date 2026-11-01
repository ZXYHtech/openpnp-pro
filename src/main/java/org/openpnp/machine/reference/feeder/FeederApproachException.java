package org.openpnp.machine.reference.feeder;

/** An approach failure must never be retried as a tape-advance failure. */
public class FeederApproachException extends Exception {
    public FeederApproachException(Throwable cause) {
        super("飞达进场失败，未执行上料，已停止自动重试：" + cause.getMessage(), cause);
    }
}
