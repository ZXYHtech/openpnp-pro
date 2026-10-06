/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

class SafetyWorkbenchModelSchemaTest {
    @Test
    void modelWithoutImportedSafeZCanBeSerialized() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();

        String json = new Gson().toJson(model);

        assertFalse(json.contains("NaN"));
        assertFalse(model.hasImportedSafeZ());
    }

    @Test
    void certificationAndToolSynchronizationKeepCurrentSchema() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        model.ensureToolProfile("H1", "N1", "H1 · N1");
        model.certify("checked");

        assertEquals(4, model.getSchemaVersion());
        assertTrue(model.isCertified());
    }

    @Test
    void heightCompensationIsPersistedAndRequiresRecertification() {
        SafetyWorkbenchModel model = new SafetyWorkbenchModel();
        model.ensureToolProfile("H1", "N1", "H1 · N1");
        model.certify("checked");

        model.setVisionHeightCompensationEnabled(true);
        String json = new Gson().toJson(model);

        assertTrue(json.contains("\"visionHeightCompensationEnabled\":true"));
        assertFalse(model.isCertified());
        model.certify("height checked");
        assertTrue(model.isCertified());
    }
}
