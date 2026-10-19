package org.openpnp.gui;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import org.openpnp.gui.components.CameraView;
import org.openpnp.machine.reference.vision.FlyingVisionEvaluation;
import org.openpnp.machine.reference.vision.FlyingVisionEvaluation.Entry;
import org.openpnp.machine.reference.vision.FlyingVisionEvaluation.Policy;
import org.openpnp.machine.reference.vision.FlyingVisionEvaluation.Status;
import org.openpnp.model.Configuration;
import org.openpnp.util.UiUtils;
import org.openpnp.util.VisionUtils;

/**
 * All task components are listed, but only individually qualified contexts may be enabled.
 */
@SuppressWarnings("serial")
public class FlyingVisionEvaluationDialog extends JDialog {
    private final MainFrame owner;
    private final EvaluationTable model = new EvaluationTable();
    private final JTable table = new JTable(model);
    private final JLabel progress = new JLabel("正在读取当前任务…");
    private final JLabel modes = new JLabel("运行任务后显示每只吸嘴实际采用的视觉方式。");
    private final JTextArea detail = new JTextArea(4, 55);
    private final JCheckBox autoEnable = new JCheckBox("自动启用通过项（精度、稳定性与耗时均通过）", true);
    private final JSpinner repeats = new JSpinner(new SpinnerNumberModel(3, 3, 10, 1));
    private final JSpinner position = new JSpinner(new SpinnerNumberModel(0.05, 0.005, 0.1, 0.005));
    private final JSpinner angle = new JSpinner(new SpinnerNumberModel(0.5, 0.05, 1.0, 0.05));
    private final JSpinner saving = new JSpinner(new SpinnerNumberModel(5.0, 0.0, 90.0, 1.0));
    private final JButton prepare = new JButton("① 准备参考并排队评估（空吸嘴）");
    private final JButton resume = new JButton("② 继续当前任务并自动评估");
    private final JButton refresh = new JButton("刷新当前任务");
    private final CameraView camera = new CameraView();
    private final Timer timer;
    private boolean preparing;

    public FlyingVisionEvaluationDialog(MainFrame owner) {
        super(owner, "飞拍自动评估与启用", false);
        this.owner = owner;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 700));
        setSize(1230, 810);
        setLocationRelativeTo(owner);
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(root);
        JPanel top = new JPanel(new GridLayout(0, 1, 0, 6));
        top.add(new JLabel("每个封装、每只吸嘴只取一颗代表器件，自动复核任务中的拍摄角度；同封装、同高度、同视觉配方共享结果。"));
        top.add(new JLabel("先空吸嘴准备参考，再随正常任务取料验证。每项三轮静拍 → 飞拍 → 静拍复核，测试后仍用普通视觉完成当颗贴装。"));
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        options.add(new JLabel("重复"));
        options.add(repeats);
        options.add(new JLabel("位置差 mm ≤"));
        options.add(position);
        options.add(new JLabel("角度差 ° ≤"));
        options.add(angle);
        options.add(new JLabel("局部耗时减少 % ≥"));
        options.add(saving);
        options.add(autoEnable);
        top.add(options);
        top.add(progress);
        top.add(modes);
        root.add(top, BorderLayout.NORTH);
        table.setRowHeight(29);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] widths = {42, 155, 85, 55, 75, 70, 55, 105, 70, 65, 90, 85, 300};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getSelectionModel().addListSelectionListener((event)->showDetail());
        JScrollPane rows = new JScrollPane(table);
        JPanel lower = new JPanel(new BorderLayout(10, 5));
        camera.setPreferredSize(new Dimension(430, 215));
        lower.add(camera, BorderLayout.WEST);
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setText("选择一行查看未通过原因和下一步。\n绿色通过也只适用于当前吸嘴、拍摄高度、角度和配置。\n局部耗时不包含从飞达长距离入场，不能当作整机节拍提升。\n关闭此窗口不停止任务或已排队评估。");
        lower.add(new JScrollPane(detail), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, rows, lower);
        split.setResizeWeight(0.65);
        root.add(split, BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(0, 4, 8, 6));
        refresh.addActionListener((event)->scan());
        actions.add(refresh);
        JButton all = new JButton("全选 / 取消");
        all.addActionListener((event)->{
            boolean value = model.rows.stream().anyMatch((row)->!row.selected);
            for (Entry row : model.rows) {
                row.selected = value;
            }
            model.fireTableDataChanged();
        });
        actions.add(all);
        prepare.addActionListener((event)->prepare());
        actions.add(prepare);
        resume.addActionListener((event)->{
            if (!Configuration.get().getMachine().isBusy() && FlyingVisionEvaluation.isArmed()) {
                owner.getJobTab().startPauseResumeJobAction.actionPerformed(event);
            }
        });
        actions.add(resume);
        JButton stop = new JButton("停止评估");
        stop.addActionListener((event)->FlyingVisionEvaluation.cancel());
        actions.add(stop);
        JButton enable = new JButton("启用通过项");
        enable.addActionListener((event)->setSelectedEnabled(true));
        actions.add(enable);
        JButton disable = new JButton("停用选中项");
        disable.addActionListener((event)->setSelectedEnabled(false));
        actions.add(disable);
        JButton csv = new JButton("复制 CSV");
        csv.addActionListener((event)->{
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(model.csv()), null);
            progress.setText("已复制逐项结果、原因和下一步。");
        });
        actions.add(csv);
        root.add(actions, BorderLayout.SOUTH);
        timer = new Timer(500, (event)->{
            progress.setText(FlyingVisionEvaluation.progress());
            modes.setText(FlyingVisionEvaluation.modeSummary());
            boolean busy = Configuration.get().getMachine().isBusy();
            prepare.setEnabled(!preparing && !busy && !FlyingVisionEvaluation.isArmed());
            refresh.setEnabled(!preparing && !busy && !FlyingVisionEvaluation.isArmed());
            resume.setEnabled(!preparing && !busy && FlyingVisionEvaluation.isArmed());
            model.fireTableRowsUpdated(0, Math.max(0, model.getRowCount() - 1));
            showDetail();
        });
        addWindowListener(new WindowAdapter(){

            @Override()
            public void windowClosed(WindowEvent event) {
                timer.stop();
                camera.setCamera(null);
            }
        });
        try {
            camera.setCamera(VisionUtils.getBottomVisionCamera());
        } catch (Exception unavailable) {
            detail.setText("底部相机未配置：" + unavailable.getMessage());
        }
        timer.start();
        scan();
    }

    private List<Entry> selected() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        List<Entry> selected = new ArrayList<>();
        for (Entry row : model.rows) {
            if (row.selected) {
                selected.add(row);
            }
        }
        return selected;
    }

    private void scan() {
        if (Configuration.get().getMachine().isBusy()) {
            return;
        }
        try {
            model.rows = FlyingVisionEvaluation.scan(owner.getJobTab().getJob());
            model.fireTableDataChanged();
            progress.setText("已按封装合并为 " + model.rows.size() + " 个吸嘴 / 高度 / 角度条件，不按料号重复测试。");
        } catch (Exception failure) {
            progress.setText(failure.getMessage());
            UiUtils.showError(failure);
        }
    }

    private void prepare() {
        List<Entry> selected = selected();
        if (selected.isEmpty()) {
            progress.setText("请先勾选要评估的器件。");
            return;
        }
        if (Configuration.get().getMachine().isBusy()) {
            return;
        }
        Policy policy = new Policy();
        policy.repeats = ((Number)repeats.getValue()).intValue();
        policy.maxPositionMm = ((Number)position.getValue()).doubleValue();
        policy.maxAngleDeg = ((Number)angle.getValue()).doubleValue();
        policy.minimumSavingPercent = ((Number)saving.getValue()).doubleValue();
        policy.autoEnable = autoEnable.isSelected();
        preparing = true;
        prepare.setEnabled(false);
        refresh.setEnabled(false);
        UiUtils.submitUiMachineTask(()->{
            FlyingVisionEvaluation.prepareAndArm(selected, policy, (message)->SwingUtilities.invokeLater(()->progress.setText(message)));
            return null;
        }, (done)->{
            preparing = false;
            model.fireTableDataChanged();
        }, (failure)->{
            preparing = false;
            progress.setText("参考准备失败：" + failure.getMessage());
            UiUtils.showError(failure);
        });
    }

    private void setSelectedEnabled(boolean value) {
        if (preparing || Configuration.get().getMachine().isBusy() || FlyingVisionEvaluation.isArmed()) {
            progress.setText("请先停止评估并等待机器空闲，再修改启用状态。");
            return;
        }
        List<Entry> rows = selected();
        if (value) {
            rows.removeIf((row)->row.status != Status.PASSED);
        }
        try {
            FlyingVisionEvaluation.setEnabled(rows, value);
            model.fireTableDataChanged();
        } catch (Exception failure) {
            UiUtils.showError(failure);
        }
    }

    private void showDetail() {
        int index = table.getSelectedRow();
        if (index < 0) {
            return;
        }
        Entry row = model.rows.get(table.convertRowIndexToModel(index));
        detail.setText(row.partId + " / " + row.nozzleName + " / " + row.tipId + "\n同封装料号：" + String.join("、", row.partIds) + "\n结果：" + state(row) + " · " + row.reason + "\n下一步：" + row.next + String.format(Locale.ROOT, "\n已验证 %d 轮，最大位置差 %.4f mm，角度差 %.3f°，静拍前后漂移 %.4f mm。", row.trials, row.maxPositionMm, row.maxAngleDeg, row.driftMm) + "\n静拍/飞拍时间为相机附近的局部对比，不能推算整机速度。更换吸嘴、校准或视觉参数后需要重验。");
    }

    static String state(Entry row) {
        switch (row.status) {
        case READY:
            return "等待取料";

        case RUNNING:
            return "正在评估";

        case PASSED:
            return row.enabled ? "通过 · 已启用" : "通过 · 未启用";

        case NO_GAIN:
            return "精度通过 · 无提速";

        case FAILED:
            return "未通过";

        case BLOCKED:
            return "条件未满足";

        default:
            return row.remaining == 0 ? "无待贴样本" : "未验证";

        }
    }

    static final class EvaluationTable extends AbstractTableModel {
        List<Entry> rows = new ArrayList<>();
        final String[] columns = {"选择", "代表料号", "封装", "吸嘴", "高度 mm", "角度 °", "待贴", "结果", "位置差", "角度差", "静拍/飞拍 ms", "耗时减少", "原因与下一步"};

        @Override()
        public int getRowCount() {
            return rows.size();
        }

        @Override()
        public int getColumnCount() {
            return columns.length;
        }

        @Override()
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override()
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : String.class;
        }

        @Override()
        public boolean isCellEditable(int r, int c) {
            return c == 0;
        }

        @Override()
        public void setValueAt(Object value, int r, int c) {
            rows.get(r).selected = Boolean.TRUE.equals(value);
        }

        @Override()
        public Object getValueAt(int r, int c) {
            Entry row = rows.get(r);
            boolean measured = row.trials > 0;
            switch (c) {
            case 0:
                return row.selected;

            case 1:
                return row.partId;

            case 2:
                return row.packageId;

            case 3:
                return row.nozzleName;

            case 4:
                return String.format(Locale.ROOT, "%.3f", row.z);

            case 5:
                return String.format(Locale.ROOT, "%.2f", row.angle);

            case 6:
                return Integer.toString(row.remaining);

            case 7:
                return state(row);

            case 8:
                return measured ? String.format(Locale.ROOT, "%.4f", row.maxPositionMm) : "—";

            case 9:
                return measured ? String.format(Locale.ROOT, "%.3f", row.maxAngleDeg) : "—";

            case 10:
                return measured ? String.format(Locale.ROOT, "%.0f / %.0f", row.staticMs, row.flyingMs) : "—";

            case 11:
                return measured && row.staticMs > 0 ? String.format(Locale.ROOT, "%.1f%%", (1 - row.flyingMs / row.staticMs) * 100) : "—";

            default:
                return row.reason + "；" + row.next;

            }
        }

        String csv() {
            StringBuilder csv = new StringBuilder();
            for (int r = -1; r < rows.size(); r++) {
                for (int c = 0; c < columns.length; c++) {
                    if (c > 0) {
                        csv.append(',');
                    }
                    csv.append('\"').append(String.valueOf(r < 0 ? columns[c] : getValueAt(r, c)).replace("\"", "\"\"")).append('\"');
                }
                csv.append('\n');
            }
            return csv.toString();
        }
    }
}
