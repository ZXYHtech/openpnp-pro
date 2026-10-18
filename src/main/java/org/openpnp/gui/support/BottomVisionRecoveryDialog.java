package org.openpnp.gui.support;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import org.openpnp.model.Part;
import org.openpnp.spi.Nozzle;

/** Operator recovery choices are Chinese even when the recognition engine reports English. */
public final class BottomVisionRecoveryDialog {
    public enum Choice {
        REPICK("重新拾取器件"),
        RETRY_VISION("重试视觉识别"),
        DISCARD_AND_SKIP("抛料并跳过"),
        KEEP_PAUSED("保持暂停"),
        ABORT("终止贴片任务");

        private final String label;

        Choice(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private BottomVisionRecoveryDialog() {
    }

    public static Choice show(Component parent, Part part, Nozzle nozzle, Throwable error) {
        Choice[] choices = Choice.values();
        int selected = JOptionPane.showOptionDialog(parent, content(part, nozzle, error),
                "底部视觉识别失败", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, choices, Choice.KEEP_PAUSED);
        return selected >= 0 && selected < choices.length ? choices[selected] : Choice.KEEP_PAUSED;
    }

    private static JPanel content(Part part, Nozzle nozzle, Throwable error) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(new JLabel("底部相机未能完成器件定位，当前贴片任务已暂停。"));
        content.add(Box.createVerticalStrut(10));
        content.add(new JLabel("器件：" + part.getId() + "    吸嘴：" + nozzle.getName()));
        content.add(Box.createVerticalStrut(12));
        content.add(description("重新拾取器件", "丢弃当前吸嘴上的器件，从飞达重新取料并再次识别。"
                + "其他吸嘴先完成当前周期，该器件随后自动重新拾取。"));
        content.add(description("重试视觉识别", "保留当前吸嘴上的器件，仅重新执行底部视觉识别，不重新取料。"));
        content.add(description("抛料并跳过", "丢弃当前吸嘴上的器件，跳过该贴片位并继续其他贴装，不重新取料。"
                + "该位号保留未贴装状态，便于后续补贴。"));
        content.add(description("保持暂停", "暂不执行动作，可先检查器件是否吸附牢固、供料和相机图像。"));
        content.add(description("终止贴片任务", "结束当前任务，按正常终止流程处理吸嘴上剩余的器件。"));
        content.add(Box.createVerticalStrut(8));

        JToggleButton details = new JToggleButton("查看原始错误信息");
        details.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(details);
        JPanel detailPanel = new JPanel(new BorderLayout(0, 5));
        detailPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailPanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        detailPanel.add(new JLabel("以下为识别引擎的原始信息，可能包含英文，可供排查问题。"), BorderLayout.NORTH);
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        JTextArea diagnostic = new JTextArea(trace.toString());
        diagnostic.setEditable(false);
        diagnostic.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(diagnostic);
        scroll.setPreferredSize(new Dimension(650, 150));
        detailPanel.add(scroll, BorderLayout.CENTER);
        detailPanel.setVisible(false);
        content.add(detailPanel);
        details.addActionListener(event -> {
            detailPanel.setVisible(details.isSelected());
            details.setText(details.isSelected() ? "收起原始错误信息" : "查看原始错误信息");
            Window window = SwingUtilities.getWindowAncestor(content);
            if (window != null) {
                window.pack();
            }
        });
        return content;
    }

    private static JTextArea description(String action, String explanation) {
        JTextArea description = new JTextArea(action + "：" + explanation, 2, 41);
        description.setEditable(false);
        description.setFocusable(false);
        description.setOpaque(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setFont(javax.swing.UIManager.getFont("Label.font"));
        description.setBorder(BorderFactory.createEmptyBorder(0, 0, 7, 0));
        description.setAlignmentX(Component.LEFT_ALIGNMENT);
        return description;
    }
}
