package org.openpnp.gui;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.imageio.ImageIO;
import org.openpnp.gui.support.PathSelectionDialog;
import org.openpnp.stencil.*;
import com.google.gson.*;

/**
 * Native Pro workspace for the app center's three-piece stencil fixture.
 */
@SuppressWarnings("serial")
public final class StencilFixtureDialog extends JDialog {

    private final StencilStore store;

    private final StencilJobs jobs;

    private final Map<String, JComponent> editors = new LinkedHashMap<>();

    private final StencilPreview preview = new StencilPreview();

    private final JLabel validation = new JLabel(), status = new JLabel("填写 PCB 尺寸后，先检查示意图再生成模型。"), timing = new JLabel(" ");

    private final JProgressBar progress = new JProgressBar(0, 100);

    private final JButton generate = new JButton("③ 生成三件打印模型"), coupon = new JButton("生成九组转轴测试件"), cancel = new JButton("取消生成");

    private final DefaultListModel<StencilJobs.State> historyModel = new DefaultListModel<>();

    private final JList<StencilJobs.State> history = new JList<>(historyModel);

    private final DefaultListModel<String> filesModel = new DefaultListModel<>();

    private final JList<String> files = new JList<>(filesModel);

    private final Map<String, String> fileKeys = new LinkedHashMap<>();

    private final JTextArea report = new JTextArea();

    private final JTextField search = new JTextField(14);

    private final DefaultListModel<StencilStore.Preset> presetModel = new DefaultListModel<>();

    private final JList<StencilStore.Preset> presets = new JList<>(presetModel);

    private final JTabbedPane viewTabs = new JTabbedPane();

    private final javax.swing.Timer timer;

    private boolean applying;

    private String currentId, loadedId, loadingId, deletedPreset;

    private StencilParameters loadedParameters;

    private List<StencilMesh> loadedMeshes = Collections.emptyList();

    private JSplitPane horizontalSplit, verticalSplit;

    private SwingWorker<List<StencilMesh>, Void> meshLoader;

    private final JSpinner angle = new JSpinner(new SpinnerNumberModel(0, 0, 90, 5));

    private final JSpinner lift = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 12.0, 1.0));

    public StencilFixtureDialog(Window owner, Path configurationDirectory) throws IOException {
        super(owner, "钢网夹具设计 · OpenPnP Pro · Java 原生版", ModalityType.MODELESS);
        Path root = configurationDirectory.resolve("stencil-fixtures");
        store = new StencilStore(root);
        jobs = StencilJobs.shared(root);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 690));
        setSize(1280, 850);
        setLocationRelativeTo(owner);
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(10, 14, 8, 14));
        JLabel heading = new JLabel("① 填写尺寸   →   ② 检查结构与公差   →   ③ 生成并打印");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 17));
        top.add(heading, BorderLayout.NORTH);
        top.add(new JLabel("三件式快拆夹具 · 支持原应用 1.14.0 参数 · 无需 Python / 浏览器 / 后台服务"), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.add(button("导入原应用参数", this::importParameters));
        actions.add(button("导出参数", this::exportParameters));
        actions.add(button("保存为启动参数", () -> store.startup(parameters())));
        actions.add(button("恢复出厂参数", () -> {
            if (confirm("恢复出厂参数？当前未保存的编辑将被替换。")) {
                apply(StencilParameters.factory());
            }
        }));
        actions.add(button("操作说明", this::help));
        top.add(actions, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        JTabbedPane left = new JTabbedPane();
        left.addTab("尺寸与选项", parameterPanel());
        left.addTab("已保存配置", presetPanel());
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, workspace());
        horizontalSplit = split;
        split.setOneTouchExpandable(true);
        split.setResizeWeight(.32);
        split.setDividerLocation(380);
        add(split, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(6, 5));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 14, 10, 14));
        validation.setName("stencil-validation");
        bottom.add(validation, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        generate.setName("stencil-generate");
        generate.addActionListener(e -> attempt(() -> start(false)));
        coupon.addActionListener(e -> attempt(() -> start(true)));
        cancel.addActionListener(e -> jobs.cancel());
        buttons.add(generate);
        buttons.add(coupon);
        buttons.add(cancel);
        bottom.add(buttons, BorderLayout.CENTER);
        JPanel state = new JPanel(new BorderLayout(6, 4));
        progress.setStringPainted(true);
        state.add(progress, BorderLayout.NORTH);
        status.setName("stencil-status");
        state.add(status, BorderLayout.CENTER);
        state.add(timing, BorderLayout.SOUTH);
        bottom.add(state, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        timer = new javax.swing.Timer(500, e -> refresh());
        addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosed(WindowEvent e) {
                timer.stop();
                preview.stop();
                if (meshLoader != null) {
                    meshLoader.cancel(true);
                }
                try {
                    JsonObject state = new JsonObject();
                    state.addProperty("width", getWidth());
                    state.addProperty("height", getHeight());
                    state.addProperty("horizontal", horizontalSplit.getDividerLocation());
                    state.addProperty("vertical", verticalSplit.getDividerLocation());
                    store.ui(state);
                } catch (IOException ignored) {
                }
            }
        });
        try {
            JsonObject windowState = store.ui();
            if (windowState.has("width")) {
                setSize(Math.max(1000, Math.min(1800, windowState.get("width").getAsInt())), Math.max(690, Math.min(1200, windowState.get("height").getAsInt())));
            }
            if (windowState.has("horizontal")) {
                horizontalSplit.setDividerLocation(windowState.get("horizontal").getAsInt());
            }
            if (windowState.has("vertical")) {
                verticalSplit.setDividerLocation(windowState.get("vertical").getAsInt());
            }
        } catch (RuntimeException ignored) {
        }
        apply(store.startup());
        refreshPresets();
        refresh();
        timer.start();
    }

    private JPanel parameterPanel() {
        JPanel out = new JPanel(new BorderLayout());
        JTabbedPane groups = new JTabbedPane();
        groups.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        for (String group : new String[] { "基本尺寸", "打印公差", "结构与减重", "刻字" }) {
            JPanel form = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(6, 7, 6, 7);
            c.anchor = GridBagConstraints.WEST;
            c.gridy = 0;
            for (StencilParameters.Field f : StencilParameters.FIELDS.values()) {
                if (f.group.equals(group)) {
                    // Preserved on import; legacy field never drove the source app's current recessed text.
                    if (f.key.equals("engrave_depth")) {
                        continue;
                    }
                    JComponent input;
                    if (f.value instanceof Boolean) {
                        input = new JCheckBox();
                        ((JCheckBox) input).addActionListener(e -> edited(f.key));
                    } else {
                        if (f.choices.length > 0) {
                            JComboBox<String> combo = new JComboBox<>(f.choices);
                            combo.setRenderer(new DefaultListCellRenderer() {

                                @Override
                                public Component getListCellRendererComponent(JList<?> list, Object v, int i, boolean s, boolean focus) {
                                    return super.getListCellRendererComponent(list, StencilParameters.displayChoice(String.valueOf(v)), i, s, focus);
                                }
                            });
                            combo.addActionListener(e -> edited(f.key));
                            input = combo;
                        } else {
                            if (f.value instanceof Number) {
                                JSpinner spinner = new JSpinner(new SpinnerNumberModel(((Number) f.value).doubleValue(), f.min, f.max, f.step));
                                spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.###"));
                                spinner.addChangeListener(e -> edited(f.key));
                                input = spinner;
                            } else {
                                JTextField text = new JTextField(15);
                                text.getDocument().addDocumentListener(document(() -> edited(f.key)));
                                input = text;
                            }
                        }
                    }
                    input.setName("stencil-" + f.key);
                    input.setToolTipText(f.label + (f.value instanceof Number && f.choices.length == 0 ? " · 范围 " + f.min + "～" + f.max + " mm" : ""));
                    editors.put(f.key, input);
                    c.gridx = 0;
                    c.weightx = 0;
                    c.fill = GridBagConstraints.NONE;
                    form.add(new JLabel(f.label), c);
                    c.gridx = 1;
                    c.weightx = 1;
                    c.fill = GridBagConstraints.HORIZONTAL;
                    form.add(input, c);
                    c.gridy++;
                }
            }
            c.gridx = 0;
            c.gridwidth = 2;
            c.weighty = 1;
            c.fill = GridBagConstraints.VERTICAL;
            form.add(Box.createVerticalGlue(), c);
            JPanel page = new JPanel(new BorderLayout());
            page.add(new JScrollPane(form), BorderLayout.CENTER);
            JTextArea note = new JTextArea(group.equals("基本尺寸") ? "先量 PCB 长、宽、厚。修改夹口距离或夹入深度会自动计算钢网上边空白距离。螺钉为 M2.5。" : group.equals("打印公差") ? "建议先生成九组转轴测试件。选定最顺畅且无明显晃动的一组 C 值后，将其填入孔直径补偿。槽深正值会使 PCB 更低。" : group.equals("刻字") ? "凹刻字高为实际高度，不会自动缩字或扩大底板。可在空白区手动定位；生成时检查孔槽、PCB 工作区和剩余 0.4 mm 底厚。" : "扩展和减重不会移动 PCB、夹口与转轴基准。底部手指孔会一直开到边缘。");
            note.setLineWrap(true);
            note.setWrapStyleWord(true);
            note.setEditable(false);
            note.setOpaque(false);
            note.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            page.add(note, BorderLayout.SOUTH);
            groups.addTab(group.equals("基本尺寸") ? "基本" : group.equals("打印公差") ? "公差" : group.equals("结构与减重") ? "结构" : "刻字", page);
        }
        out.add(groups, BorderLayout.CENTER);
        return out;
    }

    private JPanel presetPanel() {
        JPanel out = new JPanel(new BorderLayout(6, 6));
        JPanel top = new JPanel(new BorderLayout());
        top.add(new JLabel("搜索已保存配置："), BorderLayout.WEST);
        top.add(search, BorderLayout.CENTER);
        out.add(top, BorderLayout.NORTH);
        search.getDocument().addDocumentListener(document(this::refreshPresets));
        presets.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        out.add(new JScrollPane(presets), BorderLayout.CENTER);
        presets.addMouseListener(new MouseAdapter() {

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    attempt(() -> loadPreset());
                }
            }
        });
        JPanel buttons = new JPanel(new GridLayout(0, 2, 6, 6));
        buttons.add(button("载入选中配置", this::loadPreset));
        buttons.add(button("保存当前配置…", this::savePreset));
        buttons.add(button("导出选中配置", () -> {
            StencilStore.Preset p = presets.getSelectedValue();
            if (p != null) {
                saveJson(p.parameters);
            }
        }));
        buttons.add(button("删除选中配置", () -> {
            StencilStore.Preset p = presets.getSelectedValue();
            if (p != null && confirm("删除配置‘" + p.name + "’？可撤销删除。")) {
                store.deleted(p.id, true);
                deletedPreset = p.id;
                refreshPresets();
            }
        }));
        buttons.add(button("撤销最近删除", () -> {
            if (deletedPreset != null) {
                store.deleted(deletedPreset, false);
                deletedPreset = null;
                refreshPresets();
            }
        }));
        buttons.add(button("恢复已删除配置…", this::restorePreset));
        out.add(buttons, BorderLayout.SOUTH);
        return out;
    }

    private JPanel workspace() {
        JPanel out = new JPanel(new BorderLayout(6, 6));
        JPanel controls = new JPanel(new GridLayout(0, 1));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        controls.add(tools);
        JPanel pose = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        controls.add(pose);
        tools.add(button("适合窗口", preview::fit));
        tools.add(button("俯视", preview::top));
        tools.add(button("展开 / 合拢", preview::explode));
        tools.add(button("演示翻转快拆", preview::animateRelease));
        tools.add(button("复位姿态", () -> {
            angle.setValue(0);
            lift.setValue(0.0);
            preview.release(0, 0);
        }));
        pose.add(new JLabel("翻转°"));
        pose.add(angle);
        pose.add(new JLabel("抬升 mm"));
        pose.add(lift);
        ChangeListener release = e -> {
            double a = ((Number) angle.getValue()).doubleValue(), l = ((Number) lift.getValue()).doubleValue();
            if (l > 0 && a < 88) {
                angle.setValue(90);
                a = 90;
            }
            preview.release(a, l);
        };
        angle.addChangeListener(release);
        lift.addChangeListener(release);
        JCheckBox place = new JCheckBox("点击定位刻字");
        place.addActionListener(e -> {
            if (place.isSelected()) {
                setEditor("engrave", true);
                setEditor("emboss_position", "manual");
            }
            preview.placement(place.isSelected(), (x, y) -> {
                setEditor("emboss_position", "manual");
                setEditor("emboss_x", x);
                setEditor("emboss_y", y);
                edited("");
            });
        });
        pose.add(place);
        JCheckBox references = new JCheckBox("显示 PCB / 钢网", true);
        references.addActionListener(e -> preview.references(references.isSelected()));
        pose.add(references);
        out.add(controls, BorderLayout.NORTH);
        viewTabs.addTab("② 结构示意", new JPanel(new BorderLayout()));
        viewTabs.addTab("实际三维", new JPanel(new BorderLayout()));
        viewTabs.addTab("打印姿态", new JPanel(new BorderLayout()));
        ((JPanel) viewTabs.getComponentAt(0)).add(preview, BorderLayout.CENTER);
        JPanel view = new JPanel(new BorderLayout());
        view.add(viewTabs, BorderLayout.CENTER);
        viewTabs.addChangeListener(e -> {
            int i = viewTabs.getSelectedIndex();
            JPanel page = (JPanel) viewTabs.getComponentAt(i);
            page.add(preview, BorderLayout.CENTER);
            preview.threeD(i > 0);
            preview.printPose(i == 2);
            page.revalidate();
            page.repaint();
        });
        report.setEditable(false);
        report.setLineWrap(true);
        report.setWrapStyleWord(true);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JTabbedPane results = new JTabbedPane();
        JPanel result = new JPanel(new BorderLayout(5, 5));
        files.setVisibleRowCount(5);
        files.setName("stencil-files");
        result.add(new JScrollPane(files), BorderLayout.CENTER);
        JPanel resultButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        resultButtons.add(button("打开文件", () -> openSelected(false)));
        resultButtons.add(button("用 Bambu 打开", () -> openSelected(true)));
        resultButtons.add(button("打开保存目录", () -> {
            StencilJobs.State s = current();
            if (s != null) {
                Desktop.getDesktop().open(new File(s.directory));
            }
        }));
        resultButtons.add(button("导出完整 ZIP", this::exportBundle));
        resultButtons.add(button("纸质标签…", this::label));
        result.add(resultButtons, BorderLayout.SOUTH);
        results.addTab("生成文件", result);
        results.addTab("校验报告 / 使用步骤", new JScrollPane(report));
        history.setVisibleRowCount(4);
        history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        history.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && history.getSelectedValue() != null) {
                currentId = history.getSelectedValue().id;
                refreshCurrent();
            }
        });
        results.addTab("生成历史", new JScrollPane(history));
        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, view, results);
        verticalSplit = vertical;
        vertical.setOneTouchExpandable(true);
        vertical.setResizeWeight(.74);
        vertical.setDividerLocation(350);
        out.add(vertical, BorderLayout.CENTER);
        return out;
    }

    private void setEditor(String key, Object value) {
        JComponent c = editors.get(key);
        if (c instanceof JSpinner) {
            ((JSpinner) c).setValue(value);
        } else {
            if (c instanceof JTextField) {
                ((JTextField) c).setText(value.toString());
            } else {
                if (c instanceof JCheckBox) {
                    ((JCheckBox) c).setSelected((Boolean) value);
                } else {
                    if (c instanceof JComboBox) {
                        ((JComboBox<?>) c).setSelectedItem(value instanceof Number ? Integer.toString(((Number) value).intValue()) : value.toString());
                    }
                }
            }
        }
    }

    private StencilParameters parameters() throws Exception {
        boolean old = applying;
        applying = true;
        try {
            for (JComponent c : editors.values()) {
                if (c instanceof JSpinner) {
                    ((JSpinner) c).commitEdit();
                }
            }
        } finally {
            applying = old;
        }
        return readParameters();
    }

    private StencilParameters readParameters() throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("engrave_depth", legacyDepth);
        for (Map.Entry<String, JComponent> e : editors.entrySet()) {
            JComponent c = e.getValue();
            Object value;
            if (c instanceof JSpinner) {
                value = ((JSpinner) c).getValue();
            } else {
                if (c instanceof JTextField) {
                    value = ((JTextField) c).getText();
                } else {
                    if (c instanceof JCheckBox) {
                        value = ((JCheckBox) c).isSelected();
                    } else {
                        value = ((JComboBox<?>) c).getSelectedItem();
                        if (StencilParameters.FIELDS.get(e.getKey()).value instanceof Number) {
                            value = Integer.valueOf(value.toString());
                        }
                    }
                }
            }
            o.add(e.getKey(), new Gson().toJsonTree(value));
        }
        return new StencilParameters(o);
    }

    private double legacyDepth = .4;

    private void apply(StencilParameters p) {
        applying = true;
        try {
            legacyDepth = p.n("engrave_depth");
            for (String key : editors.keySet()) {
                JsonElement value = p.json().get(key);
                setEditor(key, value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsDouble() : value.getAsString());
            }
        } finally {
            applying = false;
        }
        edited("");
    }

    private void edited(String key) {
        if (applying) {
            return;
        }
        if (key.equals("top_distance") || key.equals("grip_depth")) {
            applying = true;
            try {
                setEditor("stencil_margin", number("top_distance") + number("grip_depth"));
            } finally {
                applying = false;
            }
        } else {
            if (key.equals("stencil_margin")) {
                double distance = number("stencil_margin") - number("grip_depth");
                if (distance >= 3 && distance <= 35) {
                    applying = true;
                    try {
                        setEditor("top_distance", distance);
                    } finally {
                        applying = false;
                    }
                }
            }
        }
        try {
            StencilParameters p = readParameters();
            preview.parameters(p);
            validation.setForeground(UIManager.getColor("Label.foreground"));
            validation.setText("尺寸有效 · 可生成模型。三维显示最近生成版本；当前编辑见结构示意。");
            generate.setEnabled(!jobs.busy());
            coupon.setEnabled(!jobs.busy());
        } catch (Exception ex) {
            validation.setForeground(new Color(195, 65, 45));
            validation.setText(ex.getMessage());
            generate.setEnabled(false);
            coupon.setEnabled(false);
        }
    }

    private double number(String key) {
        return ((Number) ((JSpinner) editors.get(key)).getValue()).doubleValue();
    }

    private void start(boolean coupons) throws Exception {
        StencilJobs.State s = jobs.start(parameters(), coupons);
        currentId = s.id;
        refresh();
    }

    private void refresh() {
        List<StencilJobs.State> list = jobs.history();
        String selected = currentId;
        if (historyModel.size() != list.size()) {
            historyModel.clear();
            for (StencilJobs.State s : list) {
                historyModel.addElement(s);
            }
        } else {
            for (int i = 0; i < list.size(); i++) {
                if (!historyModel.get(i).message.equals(list.get(i).message)) {
                    historyModel.set(i, list.get(i));
                }
            }
        }
        if (selected == null && !list.isEmpty()) {
            currentId = list.get(0).id;
        }
        refreshCurrent();
        cancel.setEnabled(jobs.busy());
        edited("");
    }

    private StencilJobs.State current() {
        return currentId == null ? null : jobs.get(currentId);
    }

    private void refreshCurrent() {
        StencilJobs.State s = current();
        if (s == null) {
            return;
        }
        progress.setValue(s.progress);
        status.setText(s.message.replace('\n', ' '));
        status.setToolTipText("<html>" + escape(s.message).replace("\n", "<br>") + "</html>");
        boolean active = s.status.equals("running") || s.status.equals("queued");
        timing.setText("本次生成 " + duration(s.elapsedSeconds()) + (active ? " · 预计剩余 " + (s.remainingSeconds() < 0 ? "计算中" : duration(s.remainingSeconds())) : " · 保存位置：" + s.directory));
        String previous = files.getSelectedValue();
        List<String> values = new ArrayList<>();
        Map<String, String> newKeys = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : s.files.entrySet()) {
            String name = labelName(f.getKey()) + "  ·  " + f.getValue();
            values.add(name);
            newKeys.put(name, f.getKey());
        }
        if (!fileKeys.equals(newKeys)) {
            fileKeys.clear();
            fileKeys.putAll(newKeys);
            filesModel.clear();
            for (String value : values) {
                filesModel.addElement(value);
            }
            if (previous != null) {
                files.setSelectedValue(previous, true);
            }
        }
        if (s.status.equals("complete") && !s.id.equals(loadedId) && !s.id.equals(loadingId)) {
            loadModel(s);
        }
        if (!active && !s.status.equals("complete")) {
            report.setText(s.message + "\n\n" + new StencilPlan(new StencilParameters(s.parameters)).instructions());
        }
    }

    private void loadModel(StencilJobs.State s) {
        if (meshLoader != null && !meshLoader.isDone()) {
            meshLoader.cancel(true);
        }
        loadingId = s.id;
        meshLoader = new SwingWorker<List<StencilMesh>, Void>() {

            @Override
            protected List<StencilMesh> doInBackground() throws Exception {
                List<StencilMesh> meshes = new ArrayList<>();
                for (String name : s.kind.equals("fixture") ? new String[] { "pcb_base", "stencil_lower", "stencil_upper" } : new String[] { "hinge_test" }) {
                    meshes.add(StencilMesh.read(Paths.get(s.directory).resolve(name + ".mesh.json")));
                }
                return meshes;
            }

            @Override
            protected void done() {
                if (s.id.equals(loadingId)) {
                    loadingId = null;
                }
                if (!isDisplayable() || !s.id.equals(currentId) || isCancelled()) {
                    return;
                }
                try {
                    loadedMeshes = get();
                    loadedParameters = new StencilParameters(s.parameters);
                    preview.model(loadedParameters, loadedMeshes, !s.kind.equals("fixture"));
                    loadedId = s.id;
                    viewTabs.setSelectedIndex(1);
                    report.setText(new String(Files.readAllBytes(Paths.get(s.directory).resolve("report.json")), StandardCharsets.UTF_8) + "\n\n" + new StencilPlan(loadedParameters).instructions());
                    report.setCaretPosition(0);
                } catch (Exception ex) {
                    loadedId = s.id;
                    status.setText("结果已保存，三维加载失败：" + ex.getMessage());
                }
            }
        };
        meshLoader.execute();
    }

    private void importParameters() throws Exception {
        File[] chosen = PathSelectionDialog.chooseOpen(this, "stencil.import", "导入钢网夹具参数", PathSelectionDialog.Mode.FILES, false, store.root().toFile(), new FileNameExtensionFilter("钢网夹具参数 (*.json)", "json"));
        if (chosen != null) {
            apply(StencilParameters.parse(new String(Files.readAllBytes(chosen[0].toPath()), StandardCharsets.UTF_8)));
        }
    }

    private void exportParameters() throws Exception {
        saveJson(parameters());
    }

    private void saveJson(StencilParameters p) throws Exception {
        File target = PathSelectionDialog.chooseSave(this, "stencil.export", "导出钢网夹具参数", new File("stencil_config.json"), store.root().toFile(), new FileNameExtensionFilter("参数文件 (*.json)", "json"));
        if (target != null) {
            Files.write(target.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(p.envelope()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void refreshPresets() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        presetModel.clear();
        for (StencilStore.Preset p : store.presets(false)) {
            if (p.name.toLowerCase(Locale.ROOT).contains(q)) {
                presetModel.addElement(p);
            }
        }
    }

    private void loadPreset() {
        StencilStore.Preset p = presets.getSelectedValue();
        if (p != null) {
            apply(p.parameters);
        }
    }

    private void savePreset() throws Exception {
        StencilParameters p = parameters();
        String name = JOptionPane.showInputDialog(this, "输入配置名称。同名配置会单独提示替换。", p.s("model"));
        if (name == null) {
            return;
        }
        try {
            store.save(name, p, null, 0);
        } catch (StencilStore.ReplaceRequired collision) {
            StencilStore.Preset old = collision.existing;
            String details = "已有配置：" + old.name + "\n原 PCB：" + old.parameters.n("pcb_length") + " × " + old.parameters.n("pcb_width") + " × " + old.parameters.n("pcb_thickness") + " mm\n新 PCB：" + p.n("pcb_length") + " × " + p.n("pcb_width") + " × " + p.n("pcb_thickness") + " mm\n确认替换？旧内容将备份。";
            if (!confirm(details)) {
                return;
            }
            store.save(name, p, old.id, old.revision);
        }
        refreshPresets();
    }

    private void restorePreset() throws Exception {
        List<StencilStore.Preset> deleted = store.presets(true);
        if (deleted.isEmpty()) {
            throw new IOException("没有已删除的配置");
        }
        Object p = JOptionPane.showInputDialog(this, "选择要恢复的配置", "恢复配置", JOptionPane.PLAIN_MESSAGE, null, deleted.toArray(), deleted.get(0));
        if (p != null) {
            store.deleted(((StencilStore.Preset) p).id, false);
            refreshPresets();
        }
    }

    private void openSelected(boolean bambu) throws Exception {
        StencilJobs.State s = current();
        String key = fileKeys.get(files.getSelectedValue());
        if (s == null || key == null) {
            throw new IOException("先选择一个已生成文件");
        }
        if (bambu) {
            String mesh = key.replaceAll("\\.(step|stl|mesh\\.json)$", ".3mf");
            if (!s.files.containsKey(mesh)) {
                throw new IOException("请选择底板、夹片或转轴测试的 3MF / STL / STEP 文件");
            }
            Path p = StencilJobs.file(s, mesh);
            for (String candidate : new String[] { "C:/Program Files/Bambu Studio/bambu-studio.exe", "C:/Program Files/Bambu Studio/bambu_studio.exe" }) {
                if (Files.isRegularFile(Paths.get(candidate))) {
                    new ProcessBuilder(candidate, p.toString()).start();
                    return;
                }
            }
            Desktop.getDesktop().open(p.toFile());
        } else {
            Desktop.getDesktop().open(StencilJobs.file(s, key).toFile());
        }
    }

    private void exportBundle() throws Exception {
        StencilJobs.State s = current();
        if (s == null) {
            throw new IOException("尚无生成结果");
        }
        Path source = StencilJobs.file(s, "bundle.zip");
        File target = PathSelectionDialog.chooseSave(this, "stencil.bundle", "导出完整模型包", source.toFile(), store.root().toFile(), new FileNameExtensionFilter("ZIP 模型包", "zip"));
        if (target != null && !target.toPath().toAbsolutePath().normalize().equals(source.toAbsolutePath().normalize())) {
            Files.copy(source, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void label() throws Exception {
        StencilParameters p = parameters();
        double[] paper = store.paper();
        JSpinner width = new JSpinner(new SpinnerNumberModel(paper[0], 15.0, 200.0, 1.0)), height = new JSpinner(new SpinnerNumberModel(paper[1], 10.0, 200.0, 1.0));
        JPanel pane = new JPanel(new BorderLayout(5, 5));
        JPanel sizes = new JPanel(new FlowLayout());
        sizes.add(new JLabel("宽 mm"));
        sizes.add(width);
        sizes.add(new JLabel("高 mm"));
        sizes.add(height);
        pane.add(sizes, BorderLayout.NORTH);
        JLabel image = new JLabel();
        pane.add(new JScrollPane(image), BorderLayout.CENTER);
        Runnable update = () -> image.setIcon(new ImageIcon(StencilLabel.image(p, ((Number) width.getValue()).doubleValue(), ((Number) height.getValue()).doubleValue())));
        width.addChangeListener(e -> attempt(update::run));
        height.addChangeListener(e -> attempt(update::run));
        update.run();
        Object[] options = { "系统打印…", "保存标签 PNG", "关闭" };
        int result = JOptionPane.showOptionDialog(this, pane, "纸质标签（默认 50×30 mm）", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        double w = ((Number) width.getValue()).doubleValue(), h = ((Number) height.getValue()).doubleValue();
        store.paper(w, h);
        if (result == 0) {
            StencilLabel.print(p, w, h, this);
        } else {
            if (result == 1) {
                File target = PathSelectionDialog.chooseSave(this, "stencil.label", "保存标签预览", new File("stencil_label.png"), store.root().toFile(), new FileNameExtensionFilter("PNG 标签", "png"));
                if (target != null) {
                    ImageIO.write(StencilLabel.image(p, w, h), "png", target);
                }
            }
        }
    }

    private void help() {
        JTextArea text = new JTextArea(new StencilPlan(StencilParameters.factory()).instructions() + "\n使用入口：窗口 → 钢网夹具设计；实验功能中也可打开。\n参数和结果位置：" + store.root() + "\n\n原应用命名配置可先导出为 JSON，再在此导入；不读取或修改应用中心账号数据。\n生成任务在后台执行，关闭设计窗口不会取消任务。退出 Pro 会停止生成，下次显示为中断，已完成文件保留。\n生成文件内的 cad-input.json 是冻结的原生 CAD 操作程序，报告记录来源版本和软件校验结果。\n点击定位时请选择空白顶面；三维定位须先合拢并归零翻转/抬升，打印姿态不能定位。", 22, 80);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        JOptionPane.showMessageDialog(this, new JScrollPane(text), "钢网夹具使用说明", JOptionPane.INFORMATION_MESSAGE);
    }

    private static String labelName(String key) {
        if (key.equals("bundle.zip")) {
            return "完整模型包";
        }
        if (key.startsWith("pcb_base")) {
            return "底板";
        }
        if (key.startsWith("stencil_lower")) {
            return "下夹片";
        }
        if (key.startsWith("stencil_upper")) {
            return "上夹片";
        }
        if (key.startsWith("assembly")) {
            return "装配 STEP";
        }
        if (key.startsWith("hinge_test")) {
            return "九组转轴测试";
        }
        if (key.equals("parameters.json")) {
            return "参数";
        }
        if (key.equals("report.json")) {
            return "几何校验报告";
        }
        if (key.equals("instructions.txt")) {
            return "装配与打印步骤";
        }
        return "冻结 CAD 程序";
    }

    private static String duration(long seconds) {
        return seconds >= 60 ? seconds / 60 + " 分 " + seconds % 60 + " 秒" : seconds + " 秒";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static DocumentListener document(Runnable r) {
        return new DocumentListener() {

            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        };
    }

    private boolean confirm(String text) {
        return JOptionPane.showConfirmDialog(this, text, "请确认", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private interface Operation {

        void run() throws Exception;
    }

    private JButton button(String text, Operation action) {
        JButton button = new JButton(text);
        button.addActionListener(e -> attempt(action));
        return button;
    }

    private void attempt(Operation action) {
        try {
            action.run();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.getMessage() == null ? e.toString() : e.getMessage(), "钢网夹具", JOptionPane.ERROR_MESSAGE);
        }
    }
}
