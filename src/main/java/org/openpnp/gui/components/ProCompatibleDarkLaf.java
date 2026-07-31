package org.openpnp.gui.components;

import java.io.IOException;

import javax.swing.JDialog;
import javax.swing.JFrame;

import com.formdev.flatlaf.FlatPropertiesLaf;

/**
 * A self-contained dark look and feel that does not depend on Windows dark-mode support.
 */
public final class ProCompatibleDarkLaf extends FlatPropertiesLaf {
    public static final String NAME = "兼容深色（Windows 7 / 10）";
    private static final String RESOURCE = "/themes/openpnp-pro-compatible-dark.properties";

    public ProCompatibleDarkLaf() throws IOException {
        super(NAME, ProCompatibleDarkLaf.class.getResourceAsStream(RESOURCE));
    }
    @Override
    public void initialize() {
        super.initialize();
        JFrame.setDefaultLookAndFeelDecorated(true);
        JDialog.setDefaultLookAndFeelDecorated(true);
    }

    @Override
    public void uninitialize() {
        JFrame.setDefaultLookAndFeelDecorated(false);
        JDialog.setDefaultLookAndFeelDecorated(false);
        super.uninitialize();
    }
}
