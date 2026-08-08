/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.support;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Reusable, non-modal walkthrough for experimental tools. The glass pane only
 * consumes mouse events inside its guide card, so the highlighted real control
 * remains operable. Guide actions must never initiate physical machine motion.
 */
public final class ExperimentalGuide {
    private static final Preferences PREFS =
            Preferences.userNodeForPackage(ExperimentalGuide.class);

    public static final class Step {
        private final String title;
        private final String body;
        private final Supplier<? extends Component> target;
        private final BooleanSupplier completion;
        private final String actionLabel;
        private final Runnable safeAction;

        public Step(String title, String body, Supplier<? extends Component> target,
                BooleanSupplier completion) {
            this(title, body, target, completion, null, null);
        }

        public Step(String title, String body, Supplier<? extends Component> target,
                BooleanSupplier completion, String actionLabel, Runnable safeAction) {
            this.title = Objects.requireNonNull(title);
            this.body = Objects.requireNonNull(body);
            this.target = Objects.requireNonNull(target);
            this.completion = completion == null ? () -> true : completion;
            this.actionLabel = actionLabel;
            this.safeAction = safeAction;
            if ((actionLabel == null) != (safeAction == null)) {
                throw new IllegalArgumentException("action label and callback must be paired");
            }
        }

        boolean isComplete() {
            return completion.getAsBoolean();
        }

        boolean hasSafeAction() {
            return safeAction != null;
        }

        void runSafeAction() {
            if (safeAction != null) {
                safeAction.run();
            }
        }
    }

    private final JDialog owner;
    private final String guideId;
    private final String guideTitle;
    private final List<Step> steps;
    private final GuideGlassPane glass = new GuideGlassPane();
    private final Timer refreshTimer;
    private int stepIndex;

    public ExperimentalGuide(JDialog owner, String guideId, String guideTitle, Step... steps) {
        this.owner = Objects.requireNonNull(owner);
        this.guideId = Objects.requireNonNull(guideId);
        this.guideTitle = Objects.requireNonNull(guideTitle);
        this.steps = Arrays.asList(steps);
        if (this.steps.isEmpty()) {
            throw new IllegalArgumentException("guide must contain at least one step");
        }
        refreshTimer = new Timer(180, event -> glass.refreshState());
        refreshTimer.setCoalesce(true);
        owner.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                glass.repositionCard();
            }
        });
        owner.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                stop(false);
            }
        });
    }

    public void start() {
        stepIndex = 0;
        JRootPane root = owner.getRootPane();
        root.setGlassPane(glass);
        glass.setVisible(true);
        refreshTimer.start();
        glass.startAnimation();
        showCurrentStep();
    }

    public void startOnFirstOpen() {
        if (!PREFS.getBoolean(preferenceKey(), false)) {
            SwingUtilities.invokeLater(this::start);
        }
    }

    public void stop(boolean completed) {
        refreshTimer.stop();
        glass.stopAnimation();
        glass.setVisible(false);
        if (completed) {
            PREFS.putBoolean(preferenceKey(), true);
        }
    }

    int getStepIndex() {
        return stepIndex;
    }

    boolean isCurrentStepComplete() {
        try {
            return steps.get(stepIndex).isComplete();
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }

    private String preferenceKey() {
        return "OpenPnPPro.experimentalGuide." + guideId + ".v1";
    }

    private void previous() {
        if (stepIndex > 0) {
            stepIndex--;
            showCurrentStep();
        }
    }

    private void next() {
        if (stepIndex + 1 < steps.size()) {
            stepIndex++;
            showCurrentStep();
        }
        else {
            stop(true);
        }
    }

    private void showCurrentStep() {
        Step step = steps.get(stepIndex);
        Component target = step.target.get();
        reveal(target);
        glass.updateStep(step, target);
    }

    private void reveal(Component target) {
        if (!(target instanceof JComponent)) {
            return;
        }
        Container parent = target.getParent();
        while (parent != null) {
            if (parent instanceof JViewport) {
                Rectangle bounds = SwingUtilities.convertRectangle(
                        target.getParent(), target.getBounds(), parent);
                ((JComponent) parent).scrollRectToVisible(bounds);
                break;
            }
            parent = parent.getParent();
        }
        target.requestFocusInWindow();
    }

    private final class GuideGlassPane extends JComponent {
        private final JPanel card = new JPanel();
        private final JLabel progress = new JLabel();
        private final JLabel title = new JLabel();
        private final JTextArea body = new JTextArea();
        private final JLabel state = new JLabel();
        private final JButton action = new JButton();
        private final JButton previous = new JButton("上一步");
        private final JButton next = new JButton("下一步");
        private final JButton close = new JButton("暂时关闭");
        private Component target;
        private Step step;
        private float pulse;
        private final Timer pulseTimer;

        GuideGlassPane() {
            setLayout(null);
            setOpaque(false);
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0x5B8FB9), 2),
                    BorderFactory.createEmptyBorder(12, 14, 12, 14)));
            card.setBackground(ModernTheme.isDark()
                    ? new Color(38, 45, 52, 248) : new Color(250, 252, 254, 248));
            progress.setForeground(ModernTheme.mutedForeground());
            title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
            body.setEditable(false);
            body.setLineWrap(true);
            body.setWrapStyleWord(true);
            body.setOpaque(false);
            body.setRows(5);
            body.setMaximumSize(new Dimension(400, 120));
            body.setAlignmentX(Component.LEFT_ALIGNMENT);
            state.setFont(state.getFont().deriveFont(Font.BOLD, 12f));
            JPanel buttons = new JPanel();
            buttons.setOpaque(false);
            buttons.add(close);
            buttons.add(action);
            buttons.add(previous);
            buttons.add(next);
            buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(progress);
            card.add(Box.createVerticalStrut(5));
            card.add(title);
            card.add(Box.createVerticalStrut(7));
            card.add(body);
            card.add(Box.createVerticalStrut(6));
            card.add(state);
            card.add(Box.createVerticalStrut(8));
            card.add(buttons);
            add(card);
            previous.addActionListener(event -> ExperimentalGuide.this.previous());
            next.addActionListener(event -> ExperimentalGuide.this.next());
            close.addActionListener(event -> stop(false));
            action.addActionListener(event -> {
                if (step != null && step.hasSafeAction()) {
                    step.runSafeAction();
                    refreshState();
                }
            });
            pulseTimer = new Timer(45, event -> {
                pulse += 0.08f;
                repaint();
            });
            pulseTimer.setCoalesce(true);
        }

        void startAnimation() {
            pulseTimer.start();
        }

        void stopAnimation() {
            pulseTimer.stop();
        }
        @Override
        public boolean contains(int x, int y) {
            return card.getBounds().contains(x, y);
        }

        void updateStep(Step newStep, Component newTarget) {
            step = newStep;
            target = newTarget;
            progress.setText(guideTitle + " · " + (stepIndex + 1) + "/" + steps.size());
            title.setText(newStep.title);
            body.setText(newStep.body);
            body.setCaretPosition(0);
            action.setVisible(newStep.hasSafeAction());
            action.setText(newStep.actionLabel == null ? "" : newStep.actionLabel);
            previous.setEnabled(stepIndex > 0);
            next.setText(stepIndex + 1 == steps.size() ? "完成引导" : "下一步");
            card.setSize(430, 250);
            repositionCard();
            refreshState();
        }

        void refreshState() {
            boolean complete = isCurrentStepComplete();
            state.setText(complete ? "● 当前步骤已完成，可以继续"
                    : "○ 请按说明操作；也可以先浏览下一步");
            state.setForeground(complete ? new Color(0x3A9A63) : new Color(0xD18A22));
            repaint();
        }

        void repositionCard() {
            if (card.getWidth() == 0) {
                return;
            }
            Rectangle highlight = targetBounds();
            int margin = 18;
            int x = getWidth() - card.getWidth() - margin;
            int y = margin;
            if (highlight != null) {
                int below = highlight.y + highlight.height + 16;
                int above = highlight.y - card.getHeight() - 16;
                if (below + card.getHeight() <= getHeight() - margin) {
                    y = below;
                }
                else if (above >= margin) {
                    y = above;
                }
                if (card.getBounds().intersects(highlight)) {
                    x = margin;
                }
            }
            card.setLocation(Math.max(margin, x), Math.max(margin, y));
        }

        private Rectangle targetBounds() {
            if (target == null || !target.isShowing()) {
                return null;
            }
            Point point = SwingUtilities.convertPoint(target, 0, 0, this);
            return new Rectangle(point.x - 7, point.y - 7,
                    target.getWidth() + 14, target.getHeight() + 14);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Rectangle bounds = targetBounds();
            if (bounds == null) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            float alpha = 0.72f + 0.22f * (float) Math.sin(pulse);
            g.setColor(new Color(59 / 255f, 155 / 255f, 219 / 255f, alpha));
            g.setStroke(new BasicStroke(3.0f));
            g.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 14, 14);
            g.setColor(new Color(59, 155, 219, 32));
            g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 14, 14);
            g.dispose();
        }
    }
}
