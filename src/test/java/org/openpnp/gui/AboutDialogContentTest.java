package org.openpnp.gui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class AboutDialogContentTest {
    @Test
    public void nativeReleaseNotesAreBundled() {
        String text = AboutDialog.loadBundledMarkdown("CHANGES.md");

        assertNotNull(text);
        assertTrue(text.length() > 100);
        assertTrue(text.contains("OpenPnP"));
    }

    @Test
    public void missingMarkdownHasVisibleFallback() {
        String text = AboutDialog.loadMarkdown("missing-about-content.md", "可见回退内容");

        assertTrue(text.contains("可见回退内容"));
    }
}
