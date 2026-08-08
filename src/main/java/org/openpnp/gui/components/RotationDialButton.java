package org.openpnp.gui.components;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Compact entry point for the same cardinal dial used by task feeder inspection. */
@SuppressWarnings("serial")
public class RotationDialButton extends JButton {
    private final JTextField rotationField;
    private final CardinalDirectionDial dial = new CardinalDirectionDial();
    private final JPopupMenu popup = new JPopupMenu();
    private final JLabel hint = new JLabel("点击方向后立即写入角度", SwingConstants.CENTER);

    public RotationDialButton(JTextField rotationField) {
        this.rotationField = rotationField;
        setPreferredSize(new Dimension(78, 30));
        setMinimumSize(new Dimension(72, 28));
        setFocusable(false);
        setToolTipText("打开方向盘：上 0°、左 90°、下 180°、右 270°");

        JPanel content = new JPanel(new BorderLayout(4, 4));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        dial.setPreferredSize(new Dimension(190, 190));
        dial.setMinimumSize(new Dimension(190, 190));
        content.add(new JLabel("按相机蓝色方向线选择", SwingConstants.CENTER), BorderLayout.NORTH);
        content.add(dial, BorderLayout.CENTER);
        content.add(hint, BorderLayout.SOUTH);
        popup.add(content);

        dial.setDirectionListener(this::applyDirection);
        addActionListener(e -> showDial());
        rotationField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateCaption(); }
            @Override public void removeUpdate(DocumentEvent e) { updateCaption(); }
            @Override public void changedUpdate(DocumentEvent e) { updateCaption(); }
        });
        updateCaption();
    }

    public static JPanel wrap(JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setOpaque(false);
        panel.add(field, BorderLayout.CENTER);
        panel.add(new RotationDialButton(field), BorderLayout.EAST);
        return panel;
    }

    private void showDial() {
        dial.setFeederRotation(parseRotation(rotationField.getText()));
        hint.setText("点击方向后立即写入角度");
        popup.show(this, 0, getHeight());
    }

    void applyDirection(int direction) {
        rotationField.setText(Integer.toString(direction));
        rotationField.postActionEvent();
        hint.setText("已设置为 " + direction + "°");
        updateCaption();
        popup.setVisible(false);
    }

    private void updateCaption() {
        double value = parseRotation(rotationField.getText());
        int nearest = CardinalDirectionDial.nearestCardinal(value);
        if (Math.abs(normalize(value - nearest)) < 0.01) {
            setText("方向 " + nearest + "°");
        }
        else {
            setText("方向盘");
        }
    }

    static double parseRotation(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Double.parseDouble(text.trim().replace(',', '.').replace("°", ""));
        }
        catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double normalize(double angle) {
        angle %= 360;
        if (angle > 180) {
            angle -= 360;
        }
        else if (angle < -180) {
            angle += 360;
        }
        return angle;
    }
}
