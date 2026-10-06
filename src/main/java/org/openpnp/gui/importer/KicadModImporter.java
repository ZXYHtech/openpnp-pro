/*
 * Copyright (C) 2023 Jason von Nieda <jason@vonnieda.org>
 *
 * This file is part of OpenPnP.
 */
package org.openpnp.gui.importer;

import java.awt.FileDialog;
import java.io.File;
import java.io.FilenameFilter;
import java.util.List;

import org.openpnp.Translations;
import org.openpnp.gui.MainFrame;
import org.openpnp.model.Footprint;
import org.openpnp.model.Footprint.Pad;

/**
 * Compatibility entry point for importing a single KiCad footprint from the native footprint
 * editor. The actual parsing and orientation conversion are shared with the package import center.
 */
public class KicadModImporter {
    private Footprint footprint = new Footprint();
    private KicadFootprintNormalizer.Result normalized;

    public KicadModImporter() throws Exception {
        FileDialog fileDialog = new FileDialog(MainFrame.get());
        fileDialog.setFilenameFilter(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".kicad_mod"); //$NON-NLS-1$
            }
        });
        fileDialog.setVisible(true);
        if (fileDialog.getFile() != null) {
            importFile(new File(fileDialog.getDirectory(), fileDialog.getFile()));
        }
    }

    /** Constructor used by tests and other non-interactive import paths. */
    public KicadModImporter(File file) throws Exception {
        importFile(file);
    }

    private void importFile(File file) throws Exception {
        try {
            KicadFootprintParser.Result parsed = new KicadFootprintParser().parse(file);
            normalized = KicadFootprintNormalizer.normalize(parsed);
            footprint = normalized.footprint;
        }
        catch (Exception e) {
            throw new Exception(Translations.getString("KicadModImporter.LoadFile.Fail")
                    + e.getMessage(), e);
                    //$NON-NLS-1$
        }
    }

    public List<Pad> getPads() {
        return footprint.getPads();
    }

    public Footprint getFootprint() {
        return footprint;
    }

    public KicadFootprintNormalizer.Result getNormalizationResult() {
        return normalized;
    }
}
