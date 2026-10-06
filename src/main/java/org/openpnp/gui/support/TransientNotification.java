/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import javax.swing.Timer;

/** A non-focusable, auto-closing notification for recoverable operator feedback. */
public final class TransientNotification {
    public static final int MOTION_LIMIT_DURATION_MS = 3000;

    private static JWindow activeWindow;
    private static Timer activeTimer;

    private TransientNotification() {
    }

    public static void showMotionLimit(Component parent) {
        show(parent, motionFailureMessage(new Exception("soft limit")),
                MOTION_LIMIT_DURATION_MS);
    }

    public static void showMotionFailure(Component parent, Throwable throwable) {
        show(parent, motionFailureMessage(throwable), MOTION_LIMIT_DURATION_MS);
    }

    public static boolean isTransientMotionFailure(Throwable throwable) {
        return isMotionLimitFailure(throwable) || isMachineNotReadyFailure(throwable);
    }

    static String motionFailureMessage(Throwable throwable) {
        if (isMotionLimitFailure(throwable)) {
            return "移动已拦截：目标超出贴装头可达范围或软限位。";
        }
        String text = joinedMessages(throwable).toLowerCase(Locale.ROOT);
        if (text.contains("not homed") || text.contains("not been homed")
                || text.contains("尚未回零") || text.contains("未回零")) {
            return "移动未执行：机器尚未回零，请先完成 Home。";
        }
        return "移动未执行：机器尚未启动或未就绪，请先启用机器。";
    }

    static boolean isMachineNotReadyFailure(Throwable throwable) {
        String text = joinedMessages(throwable).toLowerCase(Locale.ROOT);
        return text.contains("machine is not enabled")
                || text.contains("machine not enabled")
                || text.contains("machine is disabled")
                || text.contains("machine not ready")
                || text.contains("not initialized")
                || text.contains("not homed")
                || text.contains("not been homed")
                || text.contains("机器尚未启动")
                || text.contains("机器未启动")
                || text.contains("尚未回零")
                || text.contains("未回零");
    }

    private static String joinedMessages(Throwable throwable) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) {
                text.append(' ').append(cause.getMessage());
            }
        }
        return text.toString();
    }

    public static boolean isMotionLimitFailure(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("soft limit")
                    || normalized.contains("out of bounds")
                    || normalized.contains("outside reachable")
                    || normalized.contains("not reachable")
                    || normalized.contains("unreachable")
                    || normalized.contains("exceeds the nozzle reach")
                    || message.contains("超出软限位")
                    || message.contains("超出可达范围")
                    || message.contains("目标超出")) {
                return true;
            }
        }
        return false;
    }

    public static void show(Component parent, String message, int durationMs) {
        Runnable display = () -> displayOnEdt(parent, message, durationMs);
        if (SwingUtilities.isEventDispatchThread()) {
            display.run();
        }
        else {
            SwingUtilities.invokeLater(display);
        }
    }

    private static void displayOnEdt(Component parent, String message, int durationMs) {
        dismissActive();
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        if (owner == null) {
            owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        }
        activeWindow = owner == null ? new JWindow() : new JWindow(owner);
        activeWindow.setFocusableWindowState(false);
        activeWindow.setAlwaysOnTop(true);

        JTextArea text = new JTextArea(message);
        text.setEditable(false);
        text.setFocusable(false);
        text.setOpaque(true);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setForeground(new Color(255, 82, 82));
        text.setBackground(new Color(34, 34, 34));
        Font baseFont = text.getFont();
        text.setFont(baseFont.deriveFont(Font.BOLD, Math.max(14f, baseFont.getSize2D() + 2f)));
        text.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(190, 48, 48), 2),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));
        text.setPreferredSize(new Dimension(500, 54));
        activeWindow.setContentPane(text);
        activeWindow.pack();
        position(activeWindow, parent);
        activeWindow.setVisible(true);
        activeWindow.toFront();

        activeTimer = new Timer(Math.max(250, durationMs), event -> dismissActive());
        activeTimer.setRepeats(false);
        activeTimer.start();
    }

    private static void position(JWindow window, Component parent) {
        if (parent != null && parent.isShowing()) {
            Point origin = parent.getLocationOnScreen();
            int x = origin.x + Math.max(0, (parent.getWidth() - window.getWidth()) / 2);
            int y = origin.y + Math.max(0, parent.getHeight() - window.getHeight() - 28);
            window.setLocation(x, y);
        }
        else {
            window.setLocationRelativeTo(null);
        }
        GraphicsConfiguration configuration = window.getGraphicsConfiguration();
        if (configuration != null) {
            Rectangle screen = configuration.getBounds();
            int x = Math.max(screen.x, Math.min(window.getX(),
                    screen.x + screen.width - window.getWidth()));
            int y = Math.max(screen.y, Math.min(window.getY(),
                    screen.y + screen.height - window.getHeight()));
            window.setLocation(x, y);
        }
    }

    private static void dismissActive() {
        if (activeTimer != null) {
            activeTimer.stop();
            activeTimer = null;
        }
        if (activeWindow != null) {
            activeWindow.setVisible(false);
            activeWindow.dispose();
            activeWindow = null;
        }
    }
}
