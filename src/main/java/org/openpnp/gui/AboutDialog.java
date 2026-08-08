/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.border.EmptyBorder;

import org.apache.commons.io.IOUtils;
import org.openpnp.Main;
import org.openpnp.gui.components.MarkupTextPane;

@SuppressWarnings("serial")
public class AboutDialog extends JDialog {

    private final JPanel contentPanel = new JPanel();
    private MarkupTextPane releaseNotes;
    private MarkupTextPane proChangeLog;
    private MarkupTextPane credits;
    private JTabbedPane tabbedPane;

    public AboutDialog(Frame frame) {
        super(frame, true);
        createUi();

        releaseNotes.setText(loadMarkdown("CHANGES.md",
                "# OpenPnP Release Notes\n\n未找到内置原生更新日志。"));
        releaseNotes.setUri(sourceUri("CHANGES.md"));
        credits.setText(loadMarkdown("SPONSORS.md",
                "# OpenPnP Credits\n\n未找到内置贡献者信息。"));
        credits.setUri(sourceUri("SPONSORS.md"));
        proChangeLog.setText(loadMarkdown("OPENPNP_PRO_CHANGELOG_CN.md",
                "# OpenPnP Pro " + Main.getVersionString() + "\n\n未找到 Pro 更新日志。"));
    }

    private void createUi() {
        setTitle(String.format("关于 OpenPnP Pro %s", Main.getVersionString()));
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setBounds(100, 100, 720, 600);
        getContentPane().setLayout(new BorderLayout());
        JPanel buttonPane = new JPanel();
        buttonPane.setLayout(new FlowLayout(FlowLayout.RIGHT));
        getContentPane().add(buttonPane, BorderLayout.SOUTH);
        JButton okButton = new JButton("OK");
        okButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent arg0) {
                setVisible(false);
            }
        });
        okButton.setActionCommand("OK");
        buttonPane.add(okButton);
        getRootPane().setDefaultButton(okButton);

        contentPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        getContentPane().add(contentPanel, BorderLayout.CENTER);
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        JLabel lblOpenpnp = new JLabel("OpenPnP Pro");
        lblOpenpnp.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblOpenpnp.setFont(new Font("Lucida Grande", Font.BOLD, 32));
        contentPanel.add(lblOpenpnp);
        JLabel lblCopyright = new JLabel("Copyright © Jason von Nieda and OpenPnP Contributors");
        lblCopyright.setFont(new Font("Lucida Grande", Font.PLAIN, 10));
        lblCopyright.setAlignmentX(Component.CENTER_ALIGNMENT);
        contentPanel.add(lblCopyright);
        JLabel lblVersion = new JLabel("版本：" + Main.getVersion());
        lblVersion.setFont(new Font("Lucida Grande", Font.PLAIN, 10));
        lblVersion.setAlignmentX(Component.CENTER_ALIGNMENT);
        contentPanel.add(lblVersion);

        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        contentPanel.add(tabbedPane);

        releaseNotes = new MarkupTextPane();
        tabbedPane.addTab("Release Notes", null, releaseNotes, null);

        proChangeLog = new MarkupTextPane();
        tabbedPane.addTab("Pro 更新日志", null, proChangeLog, null);

        credits = new MarkupTextPane();
        tabbedPane.addTab("Credits", null, credits, null);
    }

    public void showProChangeLog() {
        tabbedPane.setSelectedComponent(proChangeLog);
    }

    static String loadMarkdown(String fileName, String fallback) {
        try {
            File file = new File(fileName);
            if (file.isFile()) {
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (!text.trim().isEmpty()) {
                    return text;
                }
            }
        }
        catch (Exception ignored) {
        }
        String bundled = loadBundledMarkdown(fileName);
        return bundled == null || bundled.trim().isEmpty() ? fallback : bundled;
    }

    static String loadBundledMarkdown(String fileName) {
        String resourceName = fileName.startsWith("/") ? fileName : "/" + fileName;
        try (InputStream stream = AboutDialog.class.getResourceAsStream(resourceName)) {
            return stream == null ? null : IOUtils.toString(stream, StandardCharsets.UTF_8.name());
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private static URI sourceUri(String fileName) {
        try {
            return new URI(Main.getSourceUri() + fileName);
        }
        catch (Exception ignored) {
            return null;
        }
    }
}
