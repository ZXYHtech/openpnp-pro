package org.openpnp.machine.reference.driver;

/** A controller rejection must stop the current transaction, including homing. */
public final class ControllerFaultException extends Exception {
    public static boolean causedBy(Throwable failure) {
        for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause()) {
            if (failure instanceof ControllerFaultException) {
                return true;
            }
        }
        return false;
    }
    public ControllerFaultException(String connection, String response) {
        super("控制器拒绝指令（" + connection + "）。\n原始返回：" + response
                + "\n已停止后续指令。请检查急停、限位和回零行程设置；排除故障后重新连接并复位。软件不会自动解锁控制器。");
    }
}
