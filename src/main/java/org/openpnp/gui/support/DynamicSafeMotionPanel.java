/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.awt.Color;
import java.awt.FlowLayout;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import org.openpnp.machine.reference.safety.DynamicSafeMotionPlanner;
import org.openpnp.machine.reference.safety.DynamicSafeMotionSettings;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.spi.Machine;

/**
 * One persistent switch, with the actual readiness state next to it.
 */
@SuppressWarnings("serial")
public final class DynamicSafeMotionPanel extends JPanel {

    private final JToggleButton toggle = new JToggleButton();

    private final JLabel status = new JLabel();

    private final SafetyWorkbenchModel model;

    private final Machine machine;
    private long checkedRevision = Long.MIN_VALUE;
    private boolean checkedCertified;
    private String cachedIssue = "";

    public DynamicSafeMotionPanel(SafetyWorkbenchModel model, Machine machine, Consumer<String> feedback) {
        super(new WrapLayout(FlowLayout.LEFT, 7, 3));
        this.model = model;
        this.machine = machine;
        setName("xyz-coordinated-motion");
        toggle.setName("enable-xyz-overlap");
        status.setName("xyz-overlap-status");
        toggle.setToolTipText("按当前配置保存。整段验证通过才联动；单帧底部视觉取帧后后台识别，与安全移动并行。结果通过前禁止贴装，失败或超时暂停；换吸嘴保持示教顺序。再次点击关闭。");
        toggle.addActionListener(event -> {
            try {
                if (toggle.isSelected()) {
                    String issue = DynamicSafeMotionPlanner.enablementIssue(model, machine);
                    if (!issue.isEmpty()) {
                        feedback.accept(issue + "。开关尚未启用。");
                        refresh();
                        return;
                    }
                    SafetyWorkbenchStore.save(model);
                }
                DynamicSafeMotionSettings.setEnabled(toggle.isSelected());
                feedback.accept(toggle.isSelected() ? "XY/Z 联动已启用并保存。下一段拾取、贴装或底部视觉定位移动会自动评估；只有整段安全且预计更快时才使用。" + "单帧预旋转底部视觉自动并行识别，结果通过前不会下降贴装；失败或超时暂停。" : "XY/Z 联动已关闭并保存，恢复原安全 Z 路径。");
            } catch (Exception failure) {
                feedback.accept("开关保存失败：" + failure.getMessage());
            }
            refresh();
        });
        add(toggle);
        add(status);
        refresh();
    }

    public void refresh() {
        boolean enabled = DynamicSafeMotionSettings.isEnabled();
        toggle.setSelected(enabled);
        toggle.setText(enabled ? "关闭 XY/Z 联动" : "一键启用 XY/Z 联动");
        if (checkedRevision != model.getRevision() || checkedCertified != model.isCertified()) {
            cachedIssue = DynamicSafeMotionPlanner.enablementIssue(model, machine);
            checkedRevision = model.getRevision();
            checkedCertified = model.isCertified();
        }
        String issue = cachedIssue;
        if (!enabled) {
            status.setText(issue.isEmpty() ? "未启用 · 模型已就绪" : "未启用 · " + issue);
            status.setForeground(new Color(145, 151, 156));
        } else {
            if (!issue.isEmpty()) {
                status.setText("已暂停 · " + issue + " · 当前保持原安全 Z");
                status.setForeground(new Color(196, 126, 45));
            } else {
                if (!machine.isEnabled() || !machine.isHomed()) {
                    status.setText("已启用 · 待连接并回零");
                    status.setForeground(new Color(196, 126, 45));
                } else {
                    status.setText("已启用 · " + DynamicSafeMotionPlanner.getRuntimeSummary());
                    status.setForeground(new Color(42, 160, 103));
                }
            }
        }
        status.setToolTipText(status.getText());
    }
}
