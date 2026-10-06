package org.openpnp.gui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JTextField;

import org.junit.jupiter.api.Test;

public class RotationDialButtonTest {
    @Test
    public void dialWritesCameraCompatibleCardinalAngleToField() {
        JTextField field = new JTextField("12.5");
        RotationDialButton button = new RotationDialButton(field);
        button.applyDirection(270);
        assertEquals("270", field.getText());
    }

    @Test
    public void buttonIsCompactButKeepsPracticalClickTarget() {
        RotationDialButton button = new RotationDialButton(new JTextField());
        assertTrue(button.getPreferredSize().width >= 72);
        assertTrue(button.getPreferredSize().height >= 28);
        assertEquals(-90.5, RotationDialButton.parseRotation("-90,5°"), 0.001);
    }
}
