package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

public class PathSelectionDialogTest {
    @Test
    public void parsesPastedWindowsPathsAndQuotes() {
        List<File> files = PathSelectionDialog.parsePaths(
                "\"C:\\KiCad\\Resistor.pretty\"; D:\\models\\R_0603.kicad_mod\n'E:\\history image.png'");

        assertEquals(3, files.size());
        assertTrue(files.get(0).getPath().endsWith("KiCad\\Resistor.pretty"));
        assertTrue(files.get(1).getPath().endsWith("models\\R_0603.kicad_mod"));
        assertTrue(files.get(2).getPath().endsWith("history image.png"));
    }

    @Test
    public void resolvesExistingParentForTypedSavePath() {
        File directory = PathSelectionDialog.resolveDirectory(
                new File("target", "new-report.csv").getAbsolutePath(), new File("."));

        assertTrue(directory.isDirectory());
    }
}
