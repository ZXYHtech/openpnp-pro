package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Part;
import org.openpnp.spi.Nozzle;

class BottomVisionRecoveryDialogTest {
    @Test
    void actualModalDialogHasChineseChoicesEvenWithEnglishLocale() throws Exception {
        for (BottomVisionRecoveryDialog.Choice expected : BottomVisionRecoveryDialog.Choice.values()) {
            inspect(expected, false, false);
        }
    }

    @Test
    void closingTheDialogKeepsTheMachinePaused() throws Exception {
        inspect(BottomVisionRecoveryDialog.Choice.KEEP_PAUSED, true, false);
    }

    @Test
    void originalEnglishDiagnosticsAreOnlyShownWhenExpanded() throws Exception {
        inspect(BottomVisionRecoveryDialog.Choice.KEEP_PAUSED, false, true);
    }

    private void inspect(BottomVisionRecoveryDialog.Choice expected, boolean close, boolean expand) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Locale previous = JOptionPane.getDefaultLocale();
        Nozzle nozzle = mock(Nozzle.class);
        when(nozzle.getName()).thenReturn("N1");
        try {
            JOptionPane.setDefaultLocale(Locale.US);
            SwingUtilities.invokeAndWait(() -> {
                Timer timer = new Timer(300, event -> {
                    JDialog dialog = null;
                    try {
                        for (Window window : Window.getWindows()) {
                            if (window instanceof JDialog && window.isShowing()
                                    && ((JDialog) window).getTitle().equals("底部视觉识别失败")) {
                                dialog = (JDialog) window;
                            }
                        }
                        assertNotNull(dialog, "Inspect the real visible dialog");
                        List<Component> components = descendants(dialog);
                        StringBuilder visibleText = new StringBuilder();
                        for (Component component : components) {
                            if (!component.isShowing()) {
                                continue;
                            }
                            if (component instanceof JLabel) {
                                visibleText.append(((JLabel) component).getText());
                            }
                            if (component instanceof JTextArea) {
                                visibleText.append(((JTextArea) component).getText());
                            }
                            if (component instanceof AbstractButton) {
                                visibleText.append(((AbstractButton) component).getText());
                            }
                        }
                        for (BottomVisionRecoveryDialog.Choice choice : BottomVisionRecoveryDialog.Choice.values()) {
                            assertNotNull(button(components, choice.toString()));
                        }
                        assertTrue(visibleText.toString().contains("丢弃当前吸嘴上的器件"));
                        assertTrue(visibleText.toString().contains("保留当前吸嘴上的器件"));
                        assertTrue(visibleText.toString().contains("不抛料，直接从飞达重新拾取"));
                        assertFalse(visibleText.toString().contains("其他吸嘴先完成当前周期"));
                        assertTrue(visibleText.toString().contains("该位号保留未贴装状态"));
                        assertFalse(visibleText.toString().contains("No results found"));
                        assertFalse(visibleText.toString().contains("Retry Vision"));
                        assertFalse(visibleText.toString().contains("Discard + Re-pick"));
                        if (expand) {
                            button(components, "查看原始错误信息").doClick();
                            assertTrue(components.stream().anyMatch(component -> component instanceof JTextArea
                                    && component.isShowing() && ((JTextArea) component).getText().contains("No results found")));
                            button(components, "收起原始错误信息").doClick();
                        }
                        if (close) {
                            dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                        }
                        else {
                            button(components, expected.toString()).doClick();
                        }
                    }
                    catch (Throwable error) {
                        failure.set(error);
                        if (dialog != null) {
                            dialog.dispose();
                        }
                    }
                });
                timer.setRepeats(false);
                timer.start();
                assertEquals(expected, BottomVisionRecoveryDialog.show(null, new Part("TEST-0603"), nozzle,
                        new Exception("No results found in vision pipeline")));
            });
        }
        finally {
            JOptionPane.setDefaultLocale(previous);
        }
        if (failure.get() != null) {
            throw new AssertionError("Actual Chinese dialog verification failed", failure.get());
        }
    }

    private static AbstractButton button(List<Component> components, String text) {
        return components.stream().filter(component -> component instanceof AbstractButton)
                .map(component -> (AbstractButton) component).filter(button -> text.equals(button.getText()))
                .findFirst().orElse(null);
    }

    private static List<Component> descendants(Component component) {
        List<Component> result = new ArrayList<>();
        result.add(component);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                result.addAll(descendants(child));
            }
        }
        return result;
    }
}
