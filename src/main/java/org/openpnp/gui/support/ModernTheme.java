/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 *
 * This file is part of OpenPnP.
 *
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package org.openpnp.gui.support;

import java.awt.Color;
import java.awt.Insets;

import javax.swing.UIManager;

/**
 * A compact visual design layer applied on top of the selected Look and Feel. It deliberately
 * relies on UI defaults instead of replacing component UIs, so user-selectable light and dark
 * themes continue to work.
 */
public final class ModernTheme {
    public static final Color ACCENT = new Color(0x168AAD);
    public static final Color SUCCESS = new Color(0x19A974);
    public static final Color DANGER = new Color(0xD64550);

    private ModernTheme() {
    }

    public static void apply() {
        Color panel = UIManager.getColor("Panel.background");
        boolean dark = panel != null && luminance(panel) < 0.45;
        boolean compatibleDark = UIManager.getBoolean("OpenPnPPro.compatibleDark");
        Color accent = compatibleDark ? new Color(0x718A95)
                : dark ? new Color(0x43B8D8) : ACCENT;
        Color selection = compatibleDark ? new Color(0x35434A)
                : dark ? new Color(0x244B59) : new Color(0xDDF3F8);
        Color divider = compatibleDark ? new Color(0x3B4347)
                : dark ? new Color(0x3A4148) : new Color(0xDCE3E8);

        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("CheckBox.arc", 5);
        UIManager.put("ProgressBar.arc", 10);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Button.margin", new Insets(7, 12, 7, 12));
        UIManager.put("ToggleButton.margin", new Insets(7, 12, 7, 12));
        UIManager.put("Table.rowHeight", 30);
        UIManager.put("Tree.rowHeight", 28);
        UIManager.put("TabbedPane.tabHeight", 38);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("SplitPane.dividerSize", 7);
        UIManager.put("Component.focusColor", accent);
        UIManager.put("ProgressBar.foreground", accent);
        UIManager.put("TabbedPane.underlineColor", accent);
        UIManager.put("TabbedPane.selectedBackground", selection);
        UIManager.put("Separator.foreground", divider);
        // Keep native confirmation dialogs understandable on Chinese production stations even
        // when the bundled runtime or Windows display language defaults Swing to English.
        UIManager.put("OptionPane.okButtonText", "确定");
        UIManager.put("OptionPane.yesButtonText", "是");
        UIManager.put("OptionPane.noButtonText", "否");
        UIManager.put("OptionPane.cancelButtonText", "取消");
        UIManager.put("FileChooser.openButtonText", "打开");
        UIManager.put("FileChooser.saveButtonText", "保存");
        UIManager.put("FileChooser.cancelButtonText", "取消");
        UIManager.put("FileChooser.openDialogTitleText", "打开");
        UIManager.put("FileChooser.saveDialogTitleText", "保存");
        UIManager.put("FileChooser.lookInLabelText", "查找范围：");
        UIManager.put("FileChooser.fileNameLabelText", "文件名：");
        UIManager.put("FileChooser.filesOfTypeLabelText", "文件类型：");
        UIManager.put("FileChooser.upFolderToolTipText", "上一级");
        UIManager.put("FileChooser.homeFolderToolTipText", "主目录");
        UIManager.put("FileChooser.newFolderToolTipText", "新建文件夹");
        UIManager.put("FileChooser.listViewButtonToolTipText", "列表");
        UIManager.put("FileChooser.detailsViewButtonToolTipText", "详细信息");
        ChineseUiLocalizer.install();
    }

    public static Color surface(boolean elevated) {
        Color panel = UIManager.getColor("Panel.background");
        if (panel == null) {
            panel = new Color(0xF4F6F8);
        }
        boolean dark = luminance(panel) < 0.45;
        if (dark) {
            return elevated ? brighten(panel, 10) : darken(panel, 5);
        }
        return elevated ? Color.WHITE : new Color(0xF4F7F9);
    }

    public static Color border() {
        return luminance(surface(false)) < 0.45 ? new Color(0x41484F) : new Color(0xDCE3E8);
    }

    public static Color mutedForeground() {
        return luminance(surface(false)) < 0.45 ? new Color(0xAEB7BF) : new Color(0x687782);
    }

    public static boolean isDark() {
        return luminance(surface(false)) < 0.45;
    }

    private static double luminance(Color color) {
        return (0.2126 * color.getRed() + 0.7152 * color.getGreen()
                + 0.0722 * color.getBlue()) / 255.0;
    }

    private static Color brighten(Color color, int amount) {
        return new Color(Math.min(255, color.getRed() + amount),
                Math.min(255, color.getGreen() + amount),
                Math.min(255, color.getBlue() + amount));
    }

    private static Color darken(Color color, int amount) {
        return new Color(Math.max(0, color.getRed() - amount),
                Math.max(0, color.getGreen() - amount),
                Math.max(0, color.getBlue() - amount));
    }
}
