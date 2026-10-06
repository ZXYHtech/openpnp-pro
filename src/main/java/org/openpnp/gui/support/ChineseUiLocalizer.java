package org.openpnp.gui.support;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ContainerEvent;
import java.awt.event.WindowEvent;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import javax.swing.table.TableColumn;

/**
 * Completes Chinese localization for legacy and third-party Swing pages that still contain
 * hard-coded English captions. The normal resource bundle remains the primary translation path;
 * this class reuses its English/Chinese value pairs and also covers dynamically-created dialogs.
 */
public final class ChineseUiLocalizer {
    private static final Map<String, String> TRANSLATIONS = buildTranslations();
    private static boolean installed;

    private ChineseUiLocalizer() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent event) {
                if (event instanceof WindowEvent) {
                    WindowEvent windowEvent = (WindowEvent) event;
                    if (windowEvent.getID() == WindowEvent.WINDOW_OPENED
                            || windowEvent.getID() == WindowEvent.WINDOW_ACTIVATED) {
                        localizeTree(windowEvent.getWindow());
                    }
                }
                else if (event instanceof ContainerEvent
                        && event.getID() == ContainerEvent.COMPONENT_ADDED) {
                    localizeTree(((ContainerEvent) event).getChild());
                }
            }
        }, AWTEvent.WINDOW_EVENT_MASK | AWTEvent.CONTAINER_EVENT_MASK);
        for (Window window : Window.getWindows()) {
            localizeTree(window);
        }
    }

    public static void localizeTree(Component component) {
        if (component == null) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> localizeTree(component));
            return;
        }
        if (component instanceof Frame) {
            Frame frame = (Frame) component;
            frame.setTitle(translateText(frame.getTitle()));
        }
        else if (component instanceof Dialog) {
            Dialog dialog = (Dialog) component;
            dialog.setTitle(translateText(dialog.getTitle()));
        }
        if (component instanceof JLabel) {
            JLabel label = (JLabel) component;
            label.setText(translateText(label.getText()));
        }
        else if (component instanceof AbstractButton) {
            AbstractButton button = (AbstractButton) component;
            button.setText(translateText(button.getText()));
        }
        if (component instanceof JComponent) {
            JComponent swing = (JComponent) component;
            swing.setToolTipText(translateText(swing.getToolTipText()));
            Border border = swing.getBorder();
            if (border instanceof TitledBorder) {
                TitledBorder titled = (TitledBorder) border;
                titled.setTitle(translateText(titled.getTitle()));
            }
        }
        if (component instanceof JTabbedPane) {
            JTabbedPane tabs = (JTabbedPane) component;
            for (int i = 0; i < tabs.getTabCount(); i++) {
                tabs.setTitleAt(i, translateText(tabs.getTitleAt(i)));
                tabs.setToolTipTextAt(i, translateText(tabs.getToolTipTextAt(i)));
            }
        }
        if (component instanceof JTable) {
            JTable table = (JTable) component;
            for (int i = 0; i < table.getColumnModel().getColumnCount(); i++) {
                TableColumn column = table.getColumnModel().getColumn(i);
                if (column.getHeaderValue() instanceof String) {
                    column.setHeaderValue(translateText((String) column.getHeaderValue()));
                }
            }
            if (table.getTableHeader() != null) {
                table.getTableHeader().repaint();
            }
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                localizeTree(child);
            }
        }
    }

    /** Kept public for focused UI tests and for pages that compose dynamic captions. */
    public static String translateText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String translated = TRANSLATIONS.get(text);
        if (translated != null) {
            return translated;
        }
        if (text.startsWith("Version: ")) {
            return "版本：" + text.substring("Version: ".length());
        }
        if (text.startsWith("Error: ")) {
            return "错误：" + text.substring("Error: ".length());
        }
        if (text.startsWith("Warning: ")) {
            return "警告：" + text.substring("Warning: ".length());
        }
        return text;
    }

    /** Translate long, dynamically composed Issues & Solutions text for display only. */
    public static String translateIssueText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String translated = translateText(text);
        if (!translated.equals(text)) {
            return translated;
        }
        String[][] phrases = {
                {"Issues & Solutions", "问题与解决方案"}, {"Issue", "问题"},
                {"Solution", "解决方案"}, {"The machine", "机器"},
                {"the machine", "机器"}, {"The camera", "相机"},
                {"the camera", "相机"}, {"The nozzle tip", "吸嘴头"},
                {"the nozzle tip", "吸嘴头"}, {"The nozzle", "吸嘴"},
                {"the nozzle", "吸嘴"}, {"The axis", "轴"}, {"the axis", "轴"},
                {"The driver", "驱动器"}, {"the driver", "驱动器"},
                {"The actuator", "执行器"}, {"the actuator", "执行器"},
                {"must be configured", "必须完成配置"},
                {"is not configured", "尚未配置"}, {"is not assigned", "尚未分配"},
                {"is not enabled", "尚未启用"}, {"is disabled", "已停用"},
                {"is missing", "缺失"}, {"is invalid", "无效"},
                {"is not supported", "不受支持"}, {"is not set", "尚未设置"},
                {"should be enabled", "应当启用"}, {"should be disabled", "应当停用"},
                {"should be set to", "应设置为"}, {"should be", "应为"},
                {"Please configure", "请配置"}, {"Please select", "请选择"},
                {"Please set", "请设置"}, {"Please enable", "请启用"},
                {"Please disable", "请停用"}, {"Please calibrate", "请校准"},
                {"Please check", "请检查"}, {"Please assign", "请分配"},
                {"Configure", "配置"}, {"Calibration", "校准"},
                {"calibration", "校准"}, {"configuration", "配置"},
                {"Camera", "相机"}, {"camera", "相机"}, {"Nozzle Tip", "吸嘴头"},
                {"Nozzle", "吸嘴"}, {"nozzle tip", "吸嘴头"}, {"nozzle", "吸嘴"},
                {"Feeder", "飞达"}, {"feeder", "飞达"}, {"Actuator", "执行器"},
                {"actuator", "执行器"}, {"Controller Axis", "控制器轴"},
                {"axis", "轴"}, {"Driver", "驱动器"}, {"driver", "驱动器"},
                {"Motion Planner", "运动规划器"}, {"motion planner", "运动规划器"},
                {"Bottom Vision", "底部视觉"}, {"bottom vision", "底部视觉"},
                {"fiducial", "基准点"}, {"Fiducial", "基准点"},
                {"homing", "归零"}, {"Homing", "归零"}, {"speed", "速度"},
                {"acceleration", "加速度"}, {"resolution", "分辨率"},
                {"location", "位置"}, {"offset", "偏移"}, {"rotation", "旋转角度"},
                {"enabled", "已启用"}, {"disabled", "已停用"},
                {"required", "必需"}, {"recommended", "建议"},
                {"Warning", "警告"}, {"Error", "错误"},
                {"Information", "信息"}, {"Suggestion", "建议"},
                {"Fundamental", "基础必修"}, {"Open", "待处理"},
                {"Solved", "已解决"}, {"Dismissed", "已忽略"}
        };
        for (String[] phrase : phrases) {
            translated = translated.replace(phrase[0], phrase[1]);
        }
        return translated;
    }

    public static String translateSeverityOrState(Object value) {
        if (value == null) {
            return "";
        }
        switch (value.toString()) {
            case "Open": return "待处理";
            case "Solved": return "已解决";
            case "Dismissed": return "已忽略";
            case "None": return "无";
            case "Information": return "信息";
            case "Suggestion": return "建议";
            case "Warning": return "警告";
            case "Error": return "错误";
            case "Fundamental": return "基础必修";
            default: return translateIssueText(value.toString());
        }
    }

    private static Map<String, String> buildTranslations() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        Properties english = load("/org/openpnp/translations.properties");
        Properties chinese = load("/org/openpnp/translations_zh_CN.properties");
        for (String key : english.stringPropertyNames()) {
            String source = english.getProperty(key);
            String target = chinese.getProperty(key);
            if (source != null && target != null && !source.equals(target) && containsHan(target)) {
                result.putIfAbsent(source, target);
            }
        }

        // Common captions from legacy dialogs and vendor feeder wizards that are not resource based.
        add(result, "OK", "确定", "Cancel", "取消", "Close", "关闭", "Apply", "应用",
                "Save", "保存", "Delete", "删除", "Edit", "编辑", "Add", "添加",
                "Remove", "移除", "Reset", "重置", "Retry", "重试", "Continue", "继续",
                "Yes", "是", "No", "否", "Error", "错误", "Warning", "警告",
                "Information", "信息", "Confirm", "确认", "Select", "选择",
                "Options", "选项", "Settings", "设置", "General", "常规",
                "Advanced", "高级", "Help", "帮助", "About", "关于",
                "Rotation", "旋转角度", "Rotation in Tape", "编带内旋转角度",
                "Rotation In Tape [deg]", "编带内旋转角度 [°]", "Part", "元件",
                "Part Pitch", "元件间距", "Feed Pitch", "送料间距",
                "Feed Count", "送料次数", "Retry Count", "重试次数",
                "Actuator Name", "执行器名称", "Multiplier", "倍数",
                "Part Z", "元件 Z", "LIVE CAMERA", "实时相机", "WORKSPACE", "工作区",
                "More", "更多", "Color", "颜色", "Units", "单位", "Shape", "形状",
                "Size", "大小", "Reticle", "十字线", "Rendering Quality", "渲染质量",
                "Zoom Sensitivity", "缩放灵敏度", "Move nozzle", "移动吸嘴",
                "Estimate Z", "估算 Z", "Open", "打开", "Open File", "打开文件",
                "Open Folder", "打开文件夹", "New Folder", "新建文件夹",
                "File Name:", "文件名：", "Files of Type:", "文件类型：",
                "Look In:", "查找范围：", "Home", "主目录", "Details", "详细信息",
                "List", "列表", "Up One Level", "上一级",
                "Set Boolean Value", "设置布尔值", "On", "开启", "Off", "关闭",
                "Set Value", "设置数值", "Set", "设置", "Set Profile", "设置配置方案",
                "Read Value", "读取数值", "Read", "读取", "Read with Double", "按小数读取",
                "With", "参数", "Submit Diagnostics", "提交诊断",
                "Please Describe The Issue", "请描述问题", "Include", "包含内容",
                "Latest Log File", "最新日志文件", "Anonymous System Information", "匿名系统信息",
                "Current Job Data (Job Will Be Saved First)", "当前任务数据（将先保存任务）",
                "OpenPnP Window Screen Shot", "OpenPnP 窗口截图",
                "Vision Debug Images (10 Newest)", "视觉调试图片（最新 10 张）",
                "Estimate Z Coordinate of Object", "估算物体 Z 坐标",
                "Move Selected Nozzle to Camera", "将所选吸嘴移动到相机",
                "Units Per Tick", "每格单位", "Please select an implemention class from the list.",
                "请从列表中选择实现类。", "Templates: ", "模板：",
                "Right-click a command to pin it here", "右键单击命令可将其固定到此处",
                "Remove shortcuts", "移除快捷键", "Restore defaults", "恢复默认设置",
                "Assign", "分配", "Click here, then press the shortcut combination",
                "点击此处，然后按下快捷键组合", "PRECISION PLACEMENT WORKSPACE", "精密贴装工作区",
                "Machine intelligence, ready for production", "机器智能已就绪，可投入生产",
                "Preparing startup...", "正在准备启动……", "Timeout", "超时时间",
                "Width", "宽度", "Height", "高度", "Shift X", "X 偏移", "Shift Y", "Y 偏移",
                "Home Coordinate X", "Home 坐标 X", "Home Coordinate Y", "Home 坐标 Y",
                "Scale Factor - X", "缩放系数 - X", "Scale Factor - Y", "缩放系数 - Y",
                "Driver", "驱动器", "Name", "名称", "Peeler ID", "剥膜器 ID",
                "Feeder ID", "飞达 ID", "Feed Strength", "送料力度", "Peel Strength", "剥膜力度",
                "Feed length", "送料长度", "Peel length", "剥膜长度",
                "Change Feeder ID", "更改飞达 ID", "New ID", "新 ID",
                "Pitch In Tape [mm]", "编带间距 [mm]", "Vision Enabled?", "启用视觉？",
                "Change", "更改", "Reset offsets", "重置偏移",
                "Source Camera", "源相机", "Switcher Number", "切换器编号",
                "Exposure", "曝光", "Gain", "增益", "Pick Location", "拾取位置",
                "Hole 1 Location", "孔 1 位置", "Hole 2 Location", "孔 2 位置",
                "Normalize?", "标准化？", "Snap to Axis?", "对齐坐标轴？",
                "Vision Type", "视觉类型", "Calibration Trigger", "校准触发条件",
                "Precision Average", "平均精度", "Calibration Count", "校准次数",
                "Precision wanted", "目标精度", "Precision Confidence Limit", "精度置信限",
                "Actuator", "执行器", "Actuator Value", "执行器数值", "Feed", "送料",
                "Post Pick", "拾取后", "Move before feed", "送料前移动",
                "Current Slot Address", "当前槽位地址", "Maximum Feeder Address To Scan", "最大扫描飞达地址",
                "Search", "搜索", "Start Wizard", "启动向导", "Program Feeder Slot Wizard", "飞达槽位编程向导",
                "Next", "下一步", "Address", "地址", "Pitch", "间距",
                "Scan Start Location", "扫描起始位置", "Scan End Location", "扫描结束位置",
                "Scan Increment", "扫描步距", "Maximum Value", "最大值",
                "Voltage Reference", "参考电压", "Scale", "比例", "Offset", "偏移",
                "Input Axis", "输入轴", "Input", "输入", "Output", "输出",
                "Counter-Clockwise Axis", "逆时针轴", "Cam Radius", "凸轮半径",
                "Cam Arms Angle", "凸轮臂夹角", "Cam Wheel Radius", "凸轮轮半径",
                "Cam Wheel Gap", "凸轮轮间隙", "Factor", "系数", "Compensation?", "补偿？",
                "Map Point A", "映射点 A", "Map Point B", "映射点 B",
                "Focal Resolution", "对焦分辨率", "Averaged Frames", "平均帧数",
                "Focus Speed", "对焦速度", "Show Diagnostics?", "显示诊断？",
                "Last Focus Distance", "上次对焦距离", "Pipeline launch string", "管线启动字符串",
                "Collapse or expand the navigation menu", "收起或展开导航菜单",
                "Select the active nozzle, camera or actuator", "选择当前吸嘴、相机或执行器",
                "Automatically fit columns to the current page width", "自动调整列宽以适配当前页面");
        return Collections.unmodifiableMap(result);
    }

    private static void add(Map<String, String> map, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
    }

    private static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream stream = ChineseUiLocalizer.class.getResourceAsStream(resource)) {
            if (stream != null) {
                properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
        }
        catch (Exception ignored) {
            // Explicit fallback captions still keep critical dialogs usable.
        }
        return properties;
    }

    private static boolean containsHan(String value) {
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
}
