package org.openpnp.gui.support;

import java.awt.Image;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.ImageIcon;

/**
 * Central access to the OpenPnP Pro application mark.
 */
public final class ProBranding {
    private static final String LOGO_RESOURCE = "/branding/openpnp-pro-logo.png";
    private static final Image MASTER_LOGO = loadMasterLogo();

    private ProBranding() {
    }

    public static ImageIcon icon(int size) {
        if (MASTER_LOGO == null) {
            return null;
        }
        return new ImageIcon(MASTER_LOGO.getScaledInstance(size, size, Image.SCALE_SMOOTH));
    }

    public static List<Image> windowIcons() {
        if (MASTER_LOGO == null) {
            return Collections.emptyList();
        }
        int[] sizes = {16, 20, 24, 32, 40, 48, 64, 128, 256};
        List<Image> icons = new ArrayList<>();
        for (int size : sizes) {
            icons.add(MASTER_LOGO.getScaledInstance(size, size, Image.SCALE_SMOOTH));
        }
        return icons;
    }

    private static Image loadMasterLogo() {
        URL resource = ProBranding.class.getResource(LOGO_RESOURCE);
        return resource == null ? null : new ImageIcon(resource).getImage();
    }
}