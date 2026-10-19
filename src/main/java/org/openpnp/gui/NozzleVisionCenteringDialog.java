package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.vision.NozzleVisionCentering;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.VisionUtils;

/** Single-screen, empty-nozzle center tuning with independent post-adjustment checks. */
public class NozzleVisionCenteringDialog extends JDialog {
    private final JComboBox<ReferenceNozzle> nozzles = new JComboBox<>();
    private final JCheckBox both = new JCheckBox("依次调优全部吸嘴", true);
    private final JTextArea messages = new JTextArea(6, 80);
    private final JLabel image = new JLabel("测量时在这里显示底部相机画面", JLabel.CENTER);
    private final JLabel status = new JLabel(" ");
    private final List<JButton> buttons = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private boolean running;

    public NozzleVisionCenteringDialog(MainFrame owner) {
        super(owner, "N1 / N2 底部视觉中心自动调优", false);
        setLayout(new BorderLayout(10, 10));
        ((javax.swing.JComponent) getContentPane()).setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel top = new JPanel(new BorderLayout(5, 5));
        top.add(new JLabel("<html>清空两只吸嘴，点击一次开始。自动旋转测量、修正成像位置并换角度复核。<br>"
                + "只调整到达底部相机的位置，保留吸嘴偏心校准与贴装补偿；失败自动恢复原值。</html>"), BorderLayout.NORTH);
        JPanel choices = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Head head : Configuration.get().getMachine().getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                if (nozzle instanceof ReferenceNozzle) {
                    nozzles.addItem((ReferenceNozzle) nozzle);
                }
            }
        }
        nozzles.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list,
                        value instanceof Nozzle ? ((Nozzle) value).getName() : value, index, selected, focus);
            }
        });
        choices.add(nozzles);
        choices.add(both);
        choices.add(status);
        top.add(choices, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        image.setPreferredSize(new Dimension(820, 350));
        add(image, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        messages.setEditable(false);
        messages.setLineWrap(true);
        messages.setWrapStyleWord(true);
        messages.setFont(messages.getFont().deriveFont(14f));
        bottom.add(new JScrollPane(messages), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        button(actions, "到相机查看", () -> work(false, () -> {
            ReferenceNozzle nozzle = selected();
            if (nozzle.getPart() != null) {
                throw new Exception("请先清空吸嘴。");
            }
            Camera camera = VisionUtils.getBottomVisionCamera();
            MovableUtils.moveToLocationAtSafeZ(nozzle,
                    nozzle.getCalibrationNozzleTip().getCalibration().getCalibrationLocation(camera, nozzle));
            camera.actuateLightBeforeCapture();
            try {
                show(new NozzleVisionCentering.Progress("当前吸嘴成像位置", camera.settleAndCapture()));
            } finally {
                camera.actuateLightAfterCapture();
                nozzle.moveToSafeZ();
            }
        }));
        button(actions, "自动测量并调优", () -> {
            List<ReferenceNozzle> selected = new ArrayList<>();
            if (both.isSelected()) {
                for (int i = 0; i < nozzles.getItemCount(); i++) {
                    selected.add(nozzles.getItemAt(i));
                }
            } else {
                selected.add(selected());
            }
            work(true, () -> {
                for (ReferenceNozzle nozzle : selected) {
                    String result = NozzleVisionCentering.tune(nozzle, this::show, cancelled::get).toString();
                    SwingUtilities.invokeLater(() -> messages.append(result + "\n"));
                }
            });
        });
        button(actions, "启用", () -> settings(() -> NozzleVisionCentering.setEnabled(selected(), VisionUtils.getBottomVisionCamera(), true)));
        button(actions, "停用", () -> settings(() -> NozzleVisionCentering.setEnabled(selected(), VisionUtils.getBottomVisionCamera(), false)));
        button(actions, "撤销上次调优", () -> settings(() -> NozzleVisionCentering.undo(selected(), VisionUtils.getBottomVisionCamera())));
        JButton cancel = new JButton("取消 / 关闭");
        cancel.addActionListener(e -> close());
        actions.add(cancel);
        bottom.add(actions, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        nozzles.addActionListener(e -> refresh());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                close();
            }
        });
        refresh();
        pack();
        setLocationRelativeTo(owner);
    }

    private ReferenceNozzle selected() {
        return (ReferenceNozzle) nozzles.getSelectedItem();
    }

    private void button(JPanel panel, String name, Runnable action) {
        JButton button = new JButton(name);
        button.addActionListener(e -> action.run());
        buttons.add(button);
        panel.add(button);
    }

    private void refresh() {
        try {
            status.setText(selected() == null ? "未找到吸嘴"
                    : NozzleVisionCentering.status(selected(), VisionUtils.getBottomVisionCamera()));
        } catch (Exception failure) {
            status.setText(failure.getMessage());
        }
    }

    private void close() {
        if (running) {
            cancelled.set(true);
            messages.append("正在取消，将完成当前采图并恢复安全高度。\n");
        } else {
            dispose();
        }
    }

    private void show(NozzleVisionCentering.Progress progress) {
        SwingUtilities.invokeLater(() -> {
            status.setText(progress.message);
            if (progress.image != null) {
                int width = Math.max(1, image.getWidth()), height = Math.max(1, image.getHeight());
                double scale = Math.min((double) width / progress.image.getWidth(), (double) height / progress.image.getHeight());
                image.setText("");
                image.setIcon(new ImageIcon(progress.image.getScaledInstance(
                        Math.max(1, (int) (progress.image.getWidth() * scale)),
                        Math.max(1, (int) (progress.image.getHeight() * scale)), Image.SCALE_SMOOTH)));
            }
        });
    }

    private interface Task {
        void run() throws Exception;
    }

    private void settings(Task task) {
        if (running || Configuration.get().getMachine().isBusy()) {
            messages.append("请先等待机器空闲。\n");
            return;
        }
        for (Head head : Configuration.get().getMachine().getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                if (nozzle.getPart() != null) {
                    messages.append("请先清空吸嘴，再更改成像调优状态。\n");
                    return;
                }
            }
        }
        try {
            task.run();
            refresh();
            messages.append("调优状态已保存。\n");
        } catch (Exception failure) {
            messages.append("未完成：" + failure.getMessage() + "\n");
        }
    }

    private void work(boolean tune, Task task) {
        Machine machine = Configuration.get().getMachine();
        if (running || machine.isBusy() || !machine.isEnabled() || !machine.isHomed()) {
            messages.append("请等待机器空闲，并先启用、复位机器。\n");
            return;
        }
        running = true;
        cancelled.set(false);
        buttons.forEach(button -> button.setEnabled(false));
        nozzles.setEnabled(false);
        both.setEnabled(false);
        messages.append(tune ? "开始自动调优；校验成功后才保存并启用。\n" : "执行操作…\n");
        machine.submit(() -> {
            try {
                task.run();
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> messages.append("未完成：" + failure.getMessage() + "\n"));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    running = false;
                    buttons.forEach(button -> button.setEnabled(true));
                    nozzles.setEnabled(true);
                    both.setEnabled(true);
                    refresh();
                });
            }
            return null;
        });
    }
}
