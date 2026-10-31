package org.openpnp.gui.support;

import java.util.ArrayList;
import java.util.List;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import org.openpnp.machine.reference.signaler.SoundSignaler;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Machine;
import org.openpnp.util.UiUtils;

/** Discoverable access to the existing, stock-compatible sound signaler configuration. */
public final class TaskSoundMenu {
    private TaskSoundMenu() { }

    public static List<SoundSignaler> soundSignalers(Machine machine) {
        List<SoundSignaler> result = new ArrayList<>();
        for (var signaler : machine.getSignalers()) {
            if (signaler instanceof SoundSignaler) { result.add((SoundSignaler) signaler); }
        }
        return result;
    }

    public static void setEnabled(Machine machine, boolean errors, boolean enabled) throws Exception {
        List<SoundSignaler> signalers = soundSignalers(machine);
        if (signalers.isEmpty() && enabled) {
            SoundSignaler added = new SoundSignaler();
            added.setName("任务提示音");
            machine.addSignaler(added);
            signalers.add(added);
        }
        for (SoundSignaler signaler : signalers) {
            if (errors) { signaler.setEnableErrorSound(enabled); }
            else { signaler.setEnableFinishedSound(enabled); }
        }
    }

    public static JMenu create() {
        JMenu menu = new JMenu("任务提示音");
        JCheckBoxMenuItem error = new JCheckBoxMenuItem("任务出错提示音");
        JCheckBoxMenuItem finished = new JCheckBoxMenuItem("任务完成提示音");
        error.setToolTipText("立即生效；使用原有信号器设置，随机器配置保存。");
        finished.setToolTipText("立即生效；使用原有信号器设置，随机器配置保存。");
        menu.add(error);
        menu.add(finished);
        menu.addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent event) {
                var signalers = soundSignalers(Configuration.get().getMachine());
                error.setSelected(signalers.stream().anyMatch(SoundSignaler::isEnableErrorSound));
                finished.setSelected(signalers.stream().anyMatch(SoundSignaler::isEnableFinishedSound));
            }
            @Override public void menuDeselected(MenuEvent event) { }
            @Override public void menuCanceled(MenuEvent event) { }
        });
        error.addActionListener(event -> UiUtils.messageBoxOnException(() ->
                setEnabled(Configuration.get().getMachine(), true, error.isSelected())));
        finished.addActionListener(event -> UiUtils.messageBoxOnException(() ->
                setEnabled(Configuration.get().getMachine(), false, finished.isSelected())));
        return menu;
    }
}
