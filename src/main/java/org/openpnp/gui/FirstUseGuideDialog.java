/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.openpnp.gui.support.ModernTheme;

/** A guided, animated tour drawn directly over the real OpenPnP interface. */
public final class FirstUseGuideDialog {
    static final String PREF_COMPLETED = "FirstUseGuide.completed.v3";
    private static final int GUIDE_STEP_COUNT = 9;

    private FirstUseGuideDialog() {
    }

    public static void showIfNeeded(Window owner) {
        if (shouldShow()) {
            showAgain(owner);
        }
    }

    public static void showAgain(Window owner) {
        if (owner instanceof MainFrame) {
            new TourOverlay((MainFrame) owner).start();
        }
    }

    static boolean shouldShow() {
        return !preferences().getBoolean(PREF_COMPLETED, false);
    }

    static void markCompleted() {
        preferences().putBoolean(PREF_COMPLETED, true);
    }

    static int getGuidePageCount() {
        return GUIDE_STEP_COUNT;
    }

    private static Preferences preferences() {
        return Preferences.userNodeForPackage(FirstUseGuideDialog.class);
    }

    @SuppressWarnings("serial")
    private static class TourOverlay extends JPanel {
        private static final int CARD_MAX_WIDTH = 570;
        private static final int CARD_HEIGHT = 455;
        private final MainFrame frame;
        private final List<TourStep> steps = new ArrayList<>();
        private final JPanel card = new JPanel(new BorderLayout(0, 12));
        private final JLabel badge = new JLabel();
        private final JLabel title = new JLabel();
        private final JEditorPane description = new JEditorPane();
        private final GuideIllustration illustration = new GuideIllustration();
        private final JLabel counter = new JLabel();
        private final JButton previous = new JButton("上一步");
        private final JButton next = new JButton("下一步");
        private final Component oldGlassPane;
        private final boolean oldGlassVisible;
        private final Component originalTab;
        private int stepIndex;
        private Rectangle spotlight;
        private boolean finished;

        TourOverlay(MainFrame frame) {
            this.frame = frame;
            JRootPane rootPane = frame.getRootPane();
            oldGlassPane = rootPane.getGlassPane();
            oldGlassVisible = oldGlassPane.isVisible();
            JTabbedPane tabs = frame.getTabs();
            originalTab = tabs.getSelectedComponent();
            setOpaque(false);
            setLayout(null);
            setFocusable(true);
            buildSteps();
            buildCard();
            add(card);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent event) {
                    positionCard();
                }
            });
        }

        void start() {
            frame.getRootPane().setGlassPane(this);
            setVisible(true);
            requestFocusInWindow();
            illustration.start();
            showStep(0);
        }

        private void buildSteps() {
            steps.add(new TourStep("本次更新", "这不是换皮，而是重新组织操作流程。",
                    "<b>你最先会感受到：</b><br>顶部直接执行常用动作；列表更容易调整；"
                            + "任务物料一眼可辨；生产看板集中统计效率和异常；后台自动保存；"
                            + "悬浮相机让你离开主界面也能监控和处理问题。<br><br>"
                            + "<b>数据安全：</b>原版与 Pro 共用同一套飞达、吸嘴和机器配置，"
                            + "但不能同时运行。首次接入已有配置时，必须先完成全量安全备份。"
                            + "之后可在‘窗口 → 备份与恢复中心’按类别或文件备份、查看大小和数据项数。",
                    IllustrationMode.UPDATE, frame::getGuideCommandBarTarget, null));
            steps.add(new TourStep("左侧工作区", "想去哪里，直接点左侧，不必翻找窗口。",
                    "<b>操作：</b>单击“任务、看板、元件、封装、飞达”等入口即可换页。<br>"
                            + "空间不够时，点软件名称下方的 <b>«</b> 折叠；折叠后把鼠标放到图标上，"
                            + "仍能看到完整页面名称。",
                    IllustrationMode.NAVIGATION, frame::getGuideNavigationTarget, null));
            steps.add(new TourStep("顶部快捷任务栏", "高频动作放在手边，少切页面、少移动鼠标。",
                    "<b>这里可以直接：</b>启动、停止、回零、取料、选择工具头和调用特殊功能。<br>"
                            + "把常用菜单或按钮固定到顶部后，右键单击固定项即可移除。",
                    IllustrationMode.TOOLBAR, frame::getGuideCommandBarTarget, null));
            steps.add(new TourStep("设置快捷键", "按住 Ctrl，再用鼠标左键单击目标按钮。",
                    "<b>照着图做：</b><br>① 按住键盘 <b>Ctrl</b> 不松开；<br>"
                            + "② 把鼠标移到要设置的菜单或按钮，例如“取料”；<br>"
                            + "③ 左键单击一次，在弹窗中按下新快捷键并保存。<br>"
                            + "主快捷键和备用快捷键都可设置。",
                    IllustrationMode.SHORTCUT, frame::getJMenuBar, null));
            steps.add(new TourStep("贴片任务", "批量选中后，一次修改多行。",
                    "<b>常用方法：</b>框选或多选器件行，再修改优先级、启用状态或贴片状态，"
                            + "选中项会一起改变。<br>底部总进度会同时显示已完成、剩余器件、"
                            + "本次耗时、平均速度和预计完成时间。",
                    IllustrationMode.JOB, frame::getJobTab,
                    () -> frame.getTabs().setSelectedComponent(frame.getJobTab())));
            steps.add(new TourStep("元件与封装库", "浅绿色就是当前任务正在使用的项目。",
                    "可切换为<b>仅显示任务项</b>或<b>仅显示非任务项</b>。右键按住选中行后拖动，"
                            + "可以调整位置；双击宽度调整按钮自动适配。<br>列宽会分别记住最大化和"
                            + "小窗口状态，被遮挡的长文字悬停后显示全称。",
                    IllustrationMode.LIBRARY, frame::getPartsTab,
                    () -> frame.getTabs().setSelectedComponent(frame.getPartsTab())));
            steps.add(new TourStep("生产看板", "做完一单，就留下可复盘的数据。",
                    "这里能看到最近任务、耗时、单板数、器件数、速度、异常次数和原因建议。<br>"
                            + "选中历史任务可以重新加载；打开任务目录可直接定位原文件。",
                    IllustrationMode.DASHBOARD, frame::getGuideDashboardTarget,
                    () -> frame.getTabs().setSelectedComponent(
                            frame.getGuideDashboardTarget())));
            steps.add(new TourStep("桌面悬浮监控", "离开主页面，也能看到相机和处理异常。",
                    "<b>首次启动默认打开。</b>在“窗口 → 悬浮监控设置”中可开关并替换四个按钮。<br>"
                            + "主软件位于前台时，它会自动收起画面，只留标题和按钮；移开后恢复相机。"
                            + "可最小化或停靠屏幕边缘，鼠标悬停展开。发生错误时会强制展开，"
                            + "主窗口与悬浮窗只需确认一次。",
                    IllustrationMode.FLOATING_MONITOR, frame::getGuideStatusTarget, null));
            steps.add(new TourStep("状态与自动恢复", "工作状态集中在底部，文件在后台留有恢复副本。",
                    "底部把任务进度和机器坐标放在一起，坐标使用高对比颜色。<br>"
                            + "配置、任务、单板和拼版会定时保存到临时恢复目录；机器运动或任务繁忙时"
                            + "自动跳过，避免打断贴片。",
                    IllustrationMode.STATUS, frame::getGuideStatusTarget, null));
        }

        private void buildCard() {
            card.setBackground(ModernTheme.surface(true));
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0x4C92E8), 2),
                    BorderFactory.createEmptyBorder(17, 20, 14, 20)));

            JPanel heading = new JPanel();
            heading.setOpaque(false);
            heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
            badge.setForeground(new Color(0x4C92E8));
            badge.setFont(badge.getFont().deriveFont(Font.BOLD, 12f));
            title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
            heading.add(badge);
            heading.add(Box.createVerticalStrut(4));
            heading.add(title);
            card.add(heading, BorderLayout.NORTH);

            JPanel explanation = new JPanel(new BorderLayout(0, 9));
            explanation.setOpaque(false);
            illustration.setPreferredSize(new Dimension(500, 142));
            explanation.add(illustration, BorderLayout.NORTH);
            description.setContentType("text/html");
            description.setEditable(false);
            description.setOpaque(false);
            description.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
            description.setFont(description.getFont().deriveFont(14f));
            JScrollPane textScroll = new JScrollPane(description);
            textScroll.setBorder(null);
            textScroll.setOpaque(false);
            textScroll.getViewport().setOpaque(false);
            textScroll.setHorizontalScrollBarPolicy(
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            textScroll.getVerticalScrollBar().setUnitIncrement(12);
            explanation.add(textScroll, BorderLayout.CENTER);
            card.add(explanation, BorderLayout.CENTER);

            JPanel footer = new JPanel(new BorderLayout());
            footer.setOpaque(false);
            counter.setForeground(ModernTheme.mutedForeground());
            footer.add(counter, BorderLayout.WEST);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            actions.setOpaque(false);
            JButton skip = new JButton("跳过");
            skip.addActionListener(event -> finish());
            previous.addActionListener(event -> showStep(stepIndex - 1));
            next.addActionListener(event -> {
                if (stepIndex == steps.size() - 1) {
                    finish();
                }
                else {
                    showStep(stepIndex + 1);
                }
            });
            actions.add(skip);
            actions.add(previous);
            actions.add(next);
            footer.add(actions, BorderLayout.EAST);
            card.add(footer, BorderLayout.SOUTH);
        }

        private void showStep(int requestedIndex) {
            stepIndex = Math.max(0, Math.min(steps.size() - 1, requestedIndex));
            TourStep step = steps.get(stepIndex);
            if (step.beforeShow != null) {
                step.beforeShow.run();
            }
            badge.setText("OpenPnP Pro 2.6P2 · 界面导览 · 步骤 " + (stepIndex + 1));
            title.setText(step.title);
            String foreground = ModernTheme.isDark() ? "#E7EDF4" : "#26323A";
            description.setText("<html><body style='font-family:sans-serif;font-size:14px;"
                    + "line-height:1.45;color:" + foreground + ";margin:0'>"
                    + "<b>" + step.subtitle + "</b><br><br>" + step.description
                    + "</body></html>");
            description.setCaretPosition(0);
            illustration.setMode(step.illustration);
            counter.setText((stepIndex + 1) + " / " + steps.size());
            previous.setEnabled(stepIndex > 0);
            next.setText(stepIndex == steps.size() - 1 ? "完成" : "下一步");
            SwingUtilities.invokeLater(() -> {
                updateSpotlight();
                positionCard();
                repaint();
            });
        }

        private void updateSpotlight() {
            Component target = steps.get(stepIndex).target.get();
            if (target == null || !target.isShowing()) {
                spotlight = new Rectangle(16, 16, Math.max(0, getWidth() - 32),
                        Math.max(0, getHeight() - 32));
                return;
            }
            spotlight = SwingUtilities.convertRectangle(target.getParent(), target.getBounds(), this);
            spotlight.grow(7, 7);
            spotlight = spotlight.intersection(new Rectangle(0, 0, getWidth(), getHeight()));
        }

        private void positionCard() {
            if (spotlight == null || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            int margin = 16;
            int cardWidth = Math.min(CARD_MAX_WIDTH, Math.max(390, getWidth() - margin * 2));
            int cardHeight = Math.min(CARD_HEIGHT, getHeight() - margin * 2);
            card.setSize(cardWidth, cardHeight);

            int x;
            if (getWidth() - spotlight.x - spotlight.width >= cardWidth + margin * 2) {
                x = spotlight.x + spotlight.width + margin;
            }
            else if (spotlight.x >= cardWidth + margin * 2) {
                x = spotlight.x - cardWidth - margin;
            }
            else {
                x = Math.max(margin, Math.min(getWidth() - cardWidth - margin,
                        spotlight.x + (spotlight.width - cardWidth) / 2));
            }
            int y;
            if (spotlight.y >= cardHeight + margin * 2) {
                y = spotlight.y - cardHeight - margin;
            }
            else if (getHeight() - spotlight.y - spotlight.height >= cardHeight + margin * 2) {
                y = spotlight.y + spotlight.height + margin;
            }
            else {
                y = Math.max(margin, Math.min(getHeight() - cardHeight - margin,
                        spotlight.y + (spotlight.height - cardHeight) / 2));
            }
            card.setLocation(x, y);
            card.revalidate();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            if (spotlight == null) {
                g2.setColor(new Color(4, 11, 22, 190));
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            else {
                Area shade = new Area(new Rectangle(0, 0, getWidth(), getHeight()));
                RoundRectangle2D hole = new RoundRectangle2D.Double(spotlight.x, spotlight.y,
                        spotlight.width, spotlight.height, 18, 18);
                shade.subtract(new Area(hole));
                g2.setComposite(AlphaComposite.SrcOver);
                g2.setColor(new Color(4, 11, 22, 190));
                g2.fill(shade);
                g2.setColor(new Color(76, 146, 232, 235));
                g2.setStroke(new BasicStroke(3f));
                g2.draw(hole);
            }
            g2.dispose();
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            illustration.stop();
            markCompleted();
            if (originalTab != null && originalTab.getParent() != null) {
                frame.getTabs().setSelectedComponent(originalTab);
            }
            setVisible(false);
            frame.getRootPane().setGlassPane(oldGlassPane);
            oldGlassPane.setVisible(oldGlassVisible);
        }
    }

    private enum IllustrationMode {
        UPDATE, NAVIGATION, TOOLBAR, SHORTCUT, JOB, LIBRARY, DASHBOARD,
        FLOATING_MONITOR, STATUS
    }

    /** Lightweight vector illustrations; no image files and no loading delay. */
    @SuppressWarnings("serial")
    private static class GuideIllustration extends JPanel {
        private final Timer timer = new Timer(45, event -> advance());
        private IllustrationMode mode = IllustrationMode.UPDATE;
        private float phase;

        GuideIllustration() {
            setOpaque(false);
        }

        void start() {
            timer.start();
        }

        void stop() {
            timer.stop();
        }

        void setMode(IllustrationMode mode) {
            this.mode = mode;
            phase = 0f;
            repaint();
        }

        private void advance() {
            phase = (phase + 0.018f) % 1f;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth();
            g.setColor(ModernTheme.isDark() ? new Color(0x172231) : new Color(0xEEF5FA));
            g.fillRoundRect(0, 0, width, getHeight(), 18, 18);
            switch (mode) {
                case SHORTCUT:
                    paintShortcut(g, width);
                    break;
                case FLOATING_MONITOR:
                    paintFloatingMonitor(g, width);
                    break;
                case NAVIGATION:
                    paintNavigation(g, width);
                    break;
                case TOOLBAR:
                    paintToolbar(g, width);
                    break;
                case JOB:
                case LIBRARY:
                    paintTable(g, width, mode == IllustrationMode.LIBRARY);
                    break;
                case DASHBOARD:
                    paintDashboard(g, width);
                    break;
                case STATUS:
                    paintStatus(g, width);
                    break;
                default:
                    paintUpdate(g, width);
                    break;
            }
            g.dispose();
        }

        private void paintShortcut(Graphics2D g, int width) {
            int keyX = 28;
            int keyY = 38;
            g.setColor(new Color(0x26394D));
            g.fillRoundRect(keyX + 4, keyY + 6, 108, 62, 13, 13);
            g.setColor(new Color(0xF7FAFC));
            g.fillRoundRect(keyX, keyY, 108, 62, 13, 13);
            g.setColor(new Color(0x7F91A3));
            g.drawRoundRect(keyX, keyY, 108, 62, 13, 13);
            g.setColor(new Color(0x243446));
            g.setFont(getFont().deriveFont(Font.BOLD, 24f));
            g.drawString("Ctrl", keyX + 27, keyY + 39);

            g.setColor(new Color(0x6B7D90));
            g.setStroke(new BasicStroke(3f));
            g.drawLine(154, 69, 203, 69);
            g.drawLine(193, 60, 203, 69);
            g.drawLine(193, 78, 203, 69);

            int buttonX = Math.max(220, width - 200);
            g.setColor(new Color(0x168AAD));
            g.fillRoundRect(buttonX, 43, 128, 54, 15, 15);
            g.setColor(Color.WHITE);
            g.setFont(getFont().deriveFont(Font.BOLD, 17f));
            g.drawString("取  料", buttonX + 36, 76);

            float click = (float) Math.abs(Math.sin(phase * Math.PI * 2));
            int mouseX = buttonX + 92;
            int mouseY = 78 - Math.round(click * 16);
            g.setColor(new Color(0x25394B));
            g.fillRoundRect(mouseX, mouseY, 25, 38, 12, 12);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(mouseX, mouseY, 25, 38, 12, 12);
            g.drawLine(mouseX + 12, mouseY + 2, mouseX + 12, mouseY + 14);
            if (click > 0.72f) {
                int ring = Math.round((click - 0.72f) * 70);
                g.setColor(new Color(76, 146, 232, 180));
                g.drawOval(mouseX + 12 - ring / 2, mouseY - ring / 2, ring, ring);
            }
            g.setColor(ModernTheme.mutedForeground());
            g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            g.drawString("按住不放", keyX + 23, 123);
            g.drawString("鼠标左键单击按钮", buttonX + 8, 123);
        }

        private void paintFloatingMonitor(Graphics2D g, int width) {
            int drift = Math.round((float) Math.sin(phase * Math.PI * 2) * 8);
            int x = Math.max(30, width / 2 - 150 + drift);
            g.setColor(new Color(0x101722));
            g.fillRoundRect(x, 12, 300, 118, 13, 13);
            g.setColor(new Color(0x4C92E8));
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(x, 12, 300, 118, 13, 13);
            g.setColor(new Color(0x182433));
            g.fillRoundRect(x + 1, 13, 298, 27, 12, 12);
            g.setColor(new Color(0xE6EDF7));
            g.setFont(getFont().deriveFont(Font.BOLD, 11f));
            g.drawString("OPENPNP Pro · 实时监控", x + 10, 31);
            g.setColor(new Color(0x25384A));
            g.fillRect(x + 8, 46, 284, 46);
            g.setColor(new Color(0x43B8D8));
            g.drawOval(x + 130, 51, 38, 34);
            g.fillOval(x + 144, 63, 10, 10);
            String[] commands = {"启动", "停止", "取料", "停靠"};
            Color[] colors = {new Color(0x19A974), new Color(0xD64550),
                    new Color(0x168AAD), new Color(0x6B7D90)};
            for (int index = 0; index < commands.length; index++) {
                int bx = x + 8 + index * 72;
                g.setColor(colors[index]);
                g.fillRoundRect(bx, 98, 66, 24, 9, 9);
                g.setColor(Color.WHITE);
                g.setFont(getFont().deriveFont(Font.BOLD, 11f));
                g.drawString(commands[index], bx + 20, 114);
            }
        }

        private void paintNavigation(Graphics2D g, int width) {
            int x = 34;
            g.setColor(new Color(0x25384A));
            g.fillRoundRect(x, 12, 135, 118, 12, 12);
            String[] pages = {"任务", "生产看板", "元件", "封装"};
            int active = Math.min(pages.length - 1, (int) (phase * pages.length));
            for (int index = 0; index < pages.length; index++) {
                int y = 24 + index * 25;
                if (index == active) {
                    g.setColor(new Color(0x168AAD));
                    g.fillRoundRect(x + 8, y - 13, 119, 21, 7, 7);
                }
                g.setColor(Color.WHITE);
                g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                g.drawString(pages[index], x + 35, y + 2);
                g.fillOval(x + 15, y - 8, 9, 9);
            }
            g.setColor(ModernTheme.mutedForeground());
            g.setFont(getFont().deriveFont(Font.BOLD, 15f));
            g.drawString("单击入口，页面立即切换", Math.min(width - 225, 220), 73);
        }

        private void paintToolbar(Graphics2D g, int width) {
            String[] items = {"▶ 启动", "■ 停止", "⌂ 回零", "取料", "工具头"};
            int total = items.length * 88;
            int x = Math.max(12, (width - total) / 2);
            int active = Math.min(items.length - 1, (int) (phase * items.length));
            for (int index = 0; index < items.length; index++) {
                g.setColor(index == active ? new Color(0x168AAD) : new Color(0x33495E));
                g.fillRoundRect(x + index * 88, 45, 80, 43, 11, 11);
                g.setColor(Color.WHITE);
                g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                g.drawString(items[index], x + index * 88 + 14, 71);
            }
            g.setColor(ModernTheme.mutedForeground());
            g.drawString("常用功能固定在顶部", x, 116);
        }

        private void paintTable(Graphics2D g, int width, boolean library) {
            int x = 24;
            int tableWidth = width - 48;
            g.setColor(new Color(0x33495E));
            g.fillRoundRect(x, 15, tableWidth, 108, 10, 10);
            String[] values = library
                    ? new String[] {"R_10K", "C_100N", "LED_0603"}
                    : new String[] {"R1", "C4", "U2"};
            for (int row = 0; row < values.length; row++) {
                int y = 43 + row * 25;
                boolean highlighted = library && row != 1;
                g.setColor(highlighted ? new Color(0xBDEFD9) : new Color(0xF4F7F9));
                g.fillRoundRect(x + 8, y - 16, tableWidth - 16, 21, 5, 5);
                g.setColor(new Color(0x26323A));
                g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                g.drawString(values[row], x + 20, y);
                g.drawString(row == 1 ? "等待" : "已启用", x + tableWidth / 2, y);
            }
            int arrowY = 42 + Math.round(phase * 48);
            g.setColor(new Color(0x4C92E8));
            g.setStroke(new BasicStroke(3f));
            g.drawLine(width - 38, arrowY, width - 38, arrowY + 20);
            g.drawLine(width - 44, arrowY + 14, width - 38, arrowY + 20);
            g.drawLine(width - 32, arrowY + 14, width - 38, arrowY + 20);
        }

        private void paintDashboard(Graphics2D g, int width) {
            int base = 115;
            int[] heights = {38, 70, 52, 88, 64};
            int x = Math.max(25, width / 2 - 150);
            for (int index = 0; index < heights.length; index++) {
                int animated = Math.round(heights[index]
                        * Math.min(1f, phase * 3f + index * 0.12f));
                g.setColor(index == 3 ? new Color(0x19A974) : new Color(0x4C92E8));
                g.fillRoundRect(x + index * 62, base - animated, 40, animated, 8, 8);
            }
            g.setColor(ModernTheme.mutedForeground());
            g.drawString("任务速度与异常趋势", x + 65, 132);
        }

        private void paintStatus(Graphics2D g, int width) {
            int x = 24;
            int progressWidth = width - 210;
            g.setColor(new Color(0x33495E));
            g.fillRoundRect(x, 42, progressWidth, 38, 12, 12);
            g.setColor(new Color(0x19A974));
            g.fillRoundRect(x, 42, Math.round(progressWidth * phase), 38, 12, 12);
            g.setColor(Color.WHITE);
            g.setFont(getFont().deriveFont(Font.BOLD, 13f));
            g.drawString("贴片进度  " + Math.round(phase * 100) + "%", x + 14, 66);
            int badgeX = width - 170;
            g.setColor(new Color(0x1E648B));
            g.fillRoundRect(badgeX, 34, 145, 55, 12, 12);
            g.setColor(Color.WHITE);
            g.drawString("X 12.50  Y 8.20", badgeX + 11, 57);
            g.drawString("Z 4.00", badgeX + 11, 77);
        }

        private void paintUpdate(Graphics2D g, int width) {
            String[] labels = {"快捷任务栏", "任务看板", "自动保存", "悬浮监控"};
            int x = Math.max(16, width / 2 - 205);
            int active = Math.min(labels.length - 1, (int) (phase * labels.length));
            for (int index = 0; index < labels.length; index++) {
                int bx = x + index * 104;
                g.setColor(index == active ? new Color(0x19A974) : new Color(0x33495E));
                g.fillRoundRect(bx, 39, 94, 55, 12, 12);
                g.setColor(Color.WHITE);
                g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                g.drawString(labels[index], bx + 15, 71);
            }
            g.setColor(ModernTheme.mutedForeground());
            g.drawString("2.6  →  2.6P2", x + 151, 120);
        }
    }

    private static class TourStep {
        final String title;
        final String subtitle;
        final String description;
        final IllustrationMode illustration;
        final Supplier<Component> target;
        final Runnable beforeShow;

        TourStep(String title, String subtitle, String description,
                IllustrationMode illustration, Supplier<Component> target,
                Runnable beforeShow) {
            this.title = title;
            this.subtitle = subtitle;
            this.description = description;
            this.illustration = illustration;
            this.target = target;
            this.beforeShow = beforeShow;
        }
    }
}
